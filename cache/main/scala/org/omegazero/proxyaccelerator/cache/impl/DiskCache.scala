/*
 * Copyright (C) 2023 omegazero.org, warp03
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxyaccelerator.cache.impl;

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, ObjectInputStream};
import java.nio.charset.StandardCharsets;
import java.nio.file.{Files, FileVisitResult, Path, Paths, SimpleFileVisitor};
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.{Arrays, List};
import java.util.function.{BiConsumer, Predicate};
import java.util.zip.{DeflaterOutputStream, InflaterInputStream};

import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.util.{ArrayUtil, SerializationUtil};
import org.omegazero.proxyaccelerator.cache.{CacheEntry, ResourceCache};

object DiskCache {

	private final val logger = Logger.create();


	// https://stackoverflow.com/a/19877372
	def getDirectorySize(path: Path): Long = {
		var size = new java.util.concurrent.atomic.AtomicLong(0);
		walkFileTree(path, (file, attr) => {
			size.addAndGet(attr.size());
		});
		return size.get();
	}

	def walkFileTree(path: Path): List[(Path, BasicFileAttributes)] = {
		var m = new java.util.ArrayList[(Path, BasicFileAttributes)]();
		walkFileTree(path, (file, attr) => {
			m.add((file, attr));
		});
		return m;
	}

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

	private var enabled = false;
	private var currentSize: Long = 0;

	if(Files.isDirectory(this.cacheBaseDir)){
		this.enabled = true;
		this.currentSize = DiskCache.getDirectorySize(this.cacheBaseDir);
		logger.debug("Configured cacheBaseDir '", this.cacheBaseDir, "' has ", this.currentSize, " of ", this.maxSize, " bytes");
	}else{
		logger.warn("Given cacheBaseDir '", this.cacheBaseDir, "' is not a directory, cache will not be enabled");
	}


	override def store(primaryKey: String, entry: CacheEntry): Unit = {
		if(!this.enabled || entry.getSize() > this.maxSize)
			return;
		var filePath = this.getFilePath(primaryKey);
		var (existingSize: Long, existingMtime: Long) = if Files.isReadable(filePath) then (Files.size(filePath), Files.getLastModifiedTime(filePath).toMillis()) else (0L, 0L);
		if(System.currentTimeMillis() - existingMtime < this.rewriteDelay){
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
			this.synchronized {
				var addSize = data.length - existingSize;
				this.purgeOldEntries(addSize);
				logger.debug("Storing entry with primary key '", primaryKey, "' at '", filePath, "' (", data.length, " bytes, adding ", addSize, " bytes)");
				Files.write(filePath, data);
				this.currentSize += addSize;
			}
		}catch{
			case e: Exception => logger.warn("Error while storing entry with primary key '", primaryKey, "' to '", filePath, "': ", e);
		}
	}

	override def fetch(primaryKey: String): CacheEntry = {
		if(!this.enabled)
			return null;
		var filePath = this.getFilePath(primaryKey);
		try{
			var data = this.synchronized {
				if(!Files.isReadable(filePath))
					return null;
				Files.readAllBytes(filePath);
			};
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
		}catch{
			case e: Exception => logger.warn("Error while reading entry with primary key '", primaryKey, "' from '", filePath, "': ", e);
			return null;
		}
	}

	override def delete(primaryKey: String): CacheEntry = {
		var entry = this.fetch(primaryKey);
		if(entry == null)
			return null;
		var filePath = this.getFilePath(primaryKey);
		try{
			this.synchronized {
				logger.debug("Deleted entry with primary key '", primaryKey, "' at '", filePath, "'");
				var size = Files.size(filePath);
				Files.delete(filePath);
				this.currentSize -= size;
			}
		}catch{
			case e: Exception => logger.warn("Error while deleting entry with primary key '", primaryKey, "' at '", filePath, "': ", e);
		}
		return entry;
	}

	//override def deleteIfKey(filter: Predicate[String]): Int = {
	//}

	override def cleanup(): Unit = {
	}

	override def close(): Unit = {
	}


	private def purgeOldEntries(newSize: Long): Unit = {
		if(newSize > this.maxSize)
			throw new IllegalArgumentException("newSize is larger than maxSize");
		if(this.maxSize - this.currentSize >= newSize)
			return;
		logger.debug("Searching for oldest file to delete because maxSize will be exceeded: ", this.currentSize, " + ", newSize, " > ", this.maxSize);
		this.synchronized {
			var files = DiskCache.walkFileTree(this.cacheBaseDir);
			files.sort((e1, e2) => (e2(1).lastModifiedTime().toMillis() - e1(1).lastModifiedTime().toMillis()).toInt);
			while(this.maxSize - this.currentSize < newSize && files.size() > 0){
				var file = files.remove(files.size() - 1);
				logger.debug("Deleting oldest file '", file(0), "'");
				Files.delete(file(0));
				this.currentSize -= file(1).size();
			}
			if(files.size() == 0)
				this.currentSize = 0;
		}
	}

	private def getFilePath(primaryKey: String): Path = {
		return this.cacheBaseDir.resolve(ArrayUtil.toHexString(this.sha256(primaryKey.getBytes(StandardCharsets.UTF_8))));
	}

	private def sha256(data: Array[Byte]): Array[Byte] = {
		var md = MessageDigest.getInstance("SHA-256");
		md.update(data);
		return md.digest();
	}
}
