/*
 * Copyright (C) 2023 omegazero.org, warp03
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxyaccelerator.cache.impl;

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, ObjectInputStream, Serializable};
import java.nio.charset.StandardCharsets;
import java.nio.file.{Files, FileVisitResult, Path, Paths, SimpleFileVisitor};
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.{Arrays, List};
import java.util.function.{BiConsumer, Predicate};
import java.util.zip.{DeflaterOutputStream, InflaterInputStream};

import scala.collection.mutable.ListBuffer;
import scala.collection.mutable.Map;

import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.util.{ArrayUtil, SerializationUtil};
import org.omegazero.proxyaccelerator.cache.{CacheEntry, ResourceCache};

object DiskCache {

	private final val logger = Logger.create();


	def walkFileTree(path: Path, func: (Path, BasicFileAttributes) => Unit): Unit = {
		Files.walkFileTree(path, new SimpleFileVisitor[Path] {

			override def visitFile(file: Path, attr: BasicFileAttributes): FileVisitResult = {
				func(file, attr);
				return FileVisitResult.CONTINUE;
			}

			override def visitFileFailed(file: Path, err: IOException): FileVisitResult = FileVisitResult.CONTINUE;
			override def postVisitDirectory(dir: Path, err: IOException): FileVisitResult = FileVisitResult.CONTINUE;
		});
	}
}

class DiskCache(private var config: ConfigObject) extends ResourceCache {

	private val logger = DiskCache.logger;

	private val cacheBaseDir = Paths.get(config.getString("cacheBaseDir"));
	private val maxSize = config.optLong("maxSize", 0x40000000);
	private val compress = config.optBoolean("compress", true);
	private val rewriteDelay = config.optLong("rewriteDelay", 5000);

	private var manager: DiskCacheManager = null;

	if(Files.isDirectory(this.cacheBaseDir)){
		this.manager = new DiskCacheManager(this.cacheBaseDir, this.maxSize);
		logger.debug("Configured cacheBaseDir '", this.cacheBaseDir, "' has ", this.manager.size, " of ", this.manager.maxSize, " bytes");
	}else{
		logger.warn("Given cacheBaseDir '", this.cacheBaseDir, "' is not a directory, cache will not be enabled");
	}


	override def store(primaryKey: String, entry: CacheEntry): Unit = {
		if(this.manager == null || entry.getSize() > this.maxSize)
			return;
		var id = ArrayUtil.toHexString(this.manager.sha256(Right(primaryKey)));
		var filePath = this.manager.filePath(id);
		if(Files.isReadable(filePath) && System.currentTimeMillis() - Files.getLastModifiedTime(filePath).toMillis() < this.rewriteDelay){
			logger.debug("Skipped storing of entry with '", primaryKey, "' because file '", filePath, "' was recently modified");
			return;
		}
		try{
			var data = SerializationUtil.serialize(entry);
			var baos = new ByteArrayOutputStream();
			var hdr = 0x20;
			if(this.compress)
				hdr |= 0x01;
			baos.write(hdr);

			if(this.compress){
				var dos = new DeflaterOutputStream(baos);
				dos.write(data);
				dos.close();
			}else{
				baos.write(data);
			}
			data = baos.toByteArray();
			if(data.length > this.maxSize)
				return;
			logger.debug("Storing entry with primary key '", primaryKey, "' (", id, "; ", data.length, " bytes)");
			this.manager += (id, data, None);
		}catch{
			case e: Exception => logger.warn("Error while storing entry with primary key '", primaryKey, "' (", id, "): ", e);
		}
	}

	override def fetch(primaryKey: String): CacheEntry = {
		if(this.manager == null)
			return null;
		var id = ArrayUtil.toHexString(this.manager.sha256(Right(primaryKey)));
		try{
			var entry = this.manager.get(id);
			if(!entry.isDefined)
				return null;
			return this.readEntryData(entry.get.data);
		}catch{
			case e: Exception => logger.warn("Error while reading entry with primary key '", primaryKey, "' (", id, "): ", e);
			return null;
		}
	}

	override def delete(primaryKey: String): CacheEntry = {
		if(this.manager == null)
			return null;
		var id = ArrayUtil.toHexString(this.manager.sha256(Right(primaryKey)));
		try{
			var entry = this.manager.get(id);
			if(entry.isDefined){
				var entryData = this.readEntryData(entry.get.data);
				this.manager -= id;
				logger.debug("Deleted entry with primary key '", primaryKey, "' (", id, ")");
				return entryData;
			}else
				return null;
		}catch{
			case e: Exception => logger.warn("Error while deleting entry with primary key '", primaryKey, "' (", id, "): ", e);
			return null;
		}
	}

	override def deleteIfKey(filter: Predicate[String]): Int = {
		if(this.manager == null)
			return 0;
		try{
			return this.manager.removeIf(filter.test(_));
		}catch{
			case e: Exception => logger.warn("Error while deleting entries with Predicate: ", e);
			return 0;
		}
	}

	override def cleanup(): Unit = {
	}

	override def close(): Unit = {
	}

	private def readEntryData(data: Array[Byte]): CacheEntry = {
		var bais = new ByteArrayInputStream(data);
		var hdr = bais.read();
		var version = (hdr & 0xe0) >> 5;
		if(version != 1)
			throw new IOException("Unsupported resource version: " + version);

		var inputStream = if((hdr & 0x01) != 0) then new InflaterInputStream(bais) else bais;
		var objStream = new ObjectInputStream(inputStream);
		var obj = objStream.readObject();
		objStream.close();
		return obj.asInstanceOf[CacheEntry];
	}
}

class DiskCacheManager(val directory: Path, val maxSize: Long, checkValid: String => Boolean = (_) => true) {

	val rentries = Map[String, Entry]();
	DiskCache.walkFileTree(this.directory, (path, attrs) => {
		val fname = path.getFileName().toString();
		if(!checkValid(fname)){
			Files.delete(path);
		}else if(fname.endsWith(".ser")){
			val id = fname.substring(0, fname.length() - 4);
			if(rentries.contains(id))
				rentries(id).separateMetadata = true;
			else
				rentries += (id -> new Entry(attrs.lastModifiedTime().toMillis(), id, 0, true));
		}else{
			if(rentries.contains(fname))
				rentries(fname).size = attrs.size();
			else
				rentries += (fname -> new Entry(attrs.lastModifiedTime().toMillis(), fname, attrs.size(), false));
		}
	});

	private val diskEntries = rentries.valuesIterator.to(ListBuffer).sortWith(_.mtime < _.mtime);

	def size: Long = this.synchronized {
		var total: Long = 0;
		for(entry <- diskEntries){
			total += entry.size;
		}
		return total;
	}

	def purgeOldEntries(newSize: Long = 0): Unit = this.synchronized {
		var curSize = this.size;
		while(curSize + newSize > this.maxSize && !this.diskEntries.isEmpty){
			var entry = this.removeIndex(0);
			curSize -= entry.size;
		}
	}

	def add(id: String, data: Array[Byte], metadata: Option[Serializable] = None): Unit = this.synchronized {
		this.addSpecial(id, data.length, metadata, Files.write(_, data));
	}

	def addSpecial(id: String, dataLen: Long, metadata: Option[Serializable] = None, writeData: Path => Unit): Unit = this.synchronized {
		val existingEntry = this.diskEntries.find(_.id == id);
		if(existingEntry.isEmpty || existingEntry.get.size < dataLen)
			this.purgeOldEntries(dataLen - (if existingEntry.isDefined then existingEntry.get.size else 0));
		writeData(this.filePath(id));
		if(metadata.isDefined)
			Files.write(this.filePath(id, true), SerializationUtil.serialize(metadata.get));
		var entry: Entry = null;
		if(existingEntry.isDefined){
			entry = existingEntry.get;
			entry.mtime = System.currentTimeMillis();
			entry.size = dataLen;
		}else{
			entry = new Entry(System.currentTimeMillis(), id, dataLen, metadata.isDefined);
			if(this.diskEntries.size > 0 && this.diskEntries.last.mtime > entry.mtime)
				this.diskEntries.insert(this.diskEntries.indexWhere(_.mtime > entry.mtime), entry);
			else
				this.diskEntries += entry;
		}
	}

	def get(id: String): Option[Entry] = {
		val i = this.diskEntries.indexWhere(_.id == id);
		return if i >= 0 then Some(this.diskEntries(i)) else None;
	}

	def remove(id: String): Option[Entry] = {
		val i = this.diskEntries.indexWhere(_.id == id);
		return if i >= 0 then Some(this.removeIndex(i)) else None;
	}

	def removeIf(predicate: String => Boolean): Int = {
		var count = 0;
		var i = 0;
		while(i < diskEntries.length){
			if(predicate(this.diskEntries(i).id)){
				this.removeIndex(i);
				count = count + 1;
			}else
				i = i + 1;
		}
		return count;
	}

	private def removeIndex(index: Int): Entry = {
		val entry = this.diskEntries.remove(index);
		Files.deleteIfExists(this.filePath(entry.id, true));
		Files.deleteIfExists(this.filePath(entry.id));
		return entry;
	}

	def filePath(id: String, metadata: Boolean = false): Path = this.directory.resolve(if metadata then id + ".ser" else id);

	def += = this.add;
	def -= = this.remove;

	def sha256(data: Either[Array[Byte], String]): Array[Byte] = {
		val md = MessageDigest.getInstance("SHA-256");
		md.update(data match {
			case Left(bytes) => bytes;
			case Right(str) => str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		});
		return md.digest();
	}


	class Entry(var mtime: Long, val id: String, var size: Long, var separateMetadata: Boolean) {

		def data: Array[Byte] = Files.readAllBytes(DiskCacheManager.this.filePath(this.id));

		def metadata: Option[Object] = {
			val path = DiskCacheManager.this.filePath(this.id, true);
			return if Files.isReadable(path) then Some(SerializationUtil.deserialize(Files.readAllBytes(path))) else None;
		}
	}
}
