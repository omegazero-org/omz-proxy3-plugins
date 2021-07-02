/*
 * Copyright (C) 2021 omegazero.org
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Covered Software is provided under this License on an "as is" basis, without warranty of any kind,
 * either expressed, implied, or statutory, including, without limitation, warranties that the Covered Software
 * is free of defects, merchantable, fit for a particular purpose or non-infringing.
 * The entire risk as to the quality and performance of the Covered Software is with You.
 */
package org.omegazero.proxyplugin.mirror;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.eventbus.SubscribeEvent.Priority;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.config.ConfigArray;
import org.omegazero.proxy.config.ConfigObject;
import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxy.http.HTTPMessageData;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.ArrayUtil;
import org.omegazero.proxy.util.ProxyUtil;
import org.omegazero.proxyplugin.mirror.transformer.AuthorityTransformer;
import org.omegazero.proxyplugin.mirror.transformer.PathTransformer;

@EventBusSubscriber
public class MirrorPlugin {

	private static final Logger logger = LoggerUtil.createLogger();

	private static final byte[] EOL = new byte[] { 0xd, 0xa };
	private static final byte[] EMPTY_CHUNK = new byte[] { '0', 0xd, 0xa, 0xd, 0xa };


	private int maxChunkSize;
	private List<TransformerEntry> transformers = new ArrayList<>();

	public synchronized void configurationReload(ConfigObject config) throws UnknownHostException {
		this.maxChunkSize = config.optInt("maxChunkSize", 0x1000000);
		ConfigArray arr = config.optArray("transformers");
		if(arr == null)
			return;
		for(Object obj : arr){
			if(!(obj instanceof ConfigObject))
				continue;
			ConfigObject tObj = (ConfigObject) obj;
			String type = tObj.getString("type");
			String original = tObj.getString("original");
			String replacement = tObj.getString("replacement");
			boolean toHttp = tObj.optBoolean("toHttp", false);
			Transformer t;
			if(type.equals("authority")){
				t = new AuthorityTransformer(original, replacement, toHttp);
			}else if(type.equals("path")){
				t = new PathTransformer(original, replacement, toHttp);
			}else
				throw new IllegalArgumentException("Invalid type: " + type);
			Object so = tObj.get("hostname");
			if(so instanceof ConfigArray){
				for(Object o : (ConfigArray) so){
					if(!(o instanceof String))
						throw new IllegalArgumentException("Values in 'hostname' must be strings");
					String h = (String) o;
					this.transformers.add(new TransformerEntry(h, t));
				}
			}else if(so instanceof String){
				this.transformers.add(new TransformerEntry((String) so, t));
			}else
				throw new IllegalArgumentException("'hostname' must be a string or array");
		}
	}


	@SubscribeEvent(priority = Priority.HIGH)
	public void onHTTPResponse(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPMessage response, UpstreamServer upstreamServer)
			throws IOException {
		String reqHostname = response.getCorrespondingMessage().getOrigAuthority();
		if(reqHostname == null)
			return;
		Transformer transformer = null;
		for(TransformerEntry te : this.transformers){
			if(ProxyUtil.hostMatches(te.hostname, reqHostname)){
				transformer = te.transformer;
				break;
			}
		}
		if(transformer == null)
			return;
		response.setAttachment("mirror_transformer", transformer);
		if(response.headerExists("content-length")){
			int length;
			try{
				length = Integer.parseInt(response.getHeader("content-length"));
			}catch(NumberFormatException e){
				throw new IOException("Invalid Content-Length value", e);
			}
			response.setAttachment("mirror_totalsizeRemaining", length);
			response.setAttachment("mirror_chunkRemaining", -1);
			response.deleteHeader("content-length");
			response.setHeader("transfer-encoding", "chunked");
		}
		response.setData(this.processDataChunk(response, response.getData()));
	}

	@SubscribeEvent(priority = Priority.HIGH)
	public void onHTTPResponseData(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPMessageData responsedata, UpstreamServer upstreamServer)
			throws IOException {
		responsedata.setData(this.processDataChunk(responsedata.getHttpMessage(), responsedata.getData()));
	}


	private byte[] processDataChunk(HTTPMessage response, byte[] data) throws IOException {
		Transformer transformer = (Transformer) response.getAttachment("mirror_transformer");
		if(transformer == null)
			return data;
		Object totalRemainingAtt = response.getAttachment("mirror_totalsizeRemaining");
		if(totalRemainingAtt == null){ // chunked
			return this.transformChunkedData(transformer, response, data);
		}else{
			int totalRemaining = (int) totalRemainingAtt;
			totalRemaining -= data.length;
			response.setAttachment("mirror_totalsizeRemaining", totalRemaining);
			byte[] nd = this.transform(transformer, data, response);
			if(nd.length > 0){
				return toChunk(nd, totalRemaining <= 0);
			}else
				return new byte[0];
		}
	}

	private byte[] transformChunkedData(Transformer transformer, HTTPMessage response, byte[] data) throws IOException {
		Object remainingAtt = response.getAttachment("mirror_chunkRemaining");
		int remaining = remainingAtt != null ? (int) remainingAtt : 0;
		if(remaining < 0)
			throw new IllegalStateException("chunkRemaining is negative");
		ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
		int index = 0;
		while(index < data.length){
			if(remaining == 0){
				int chunkHeaderEnd = ArrayUtil.byteArrayIndexOf(data, EOL, index);
				if(chunkHeaderEnd < 0)
					throw new IOException("No chunk size in chunked response");
				int chunkLen;
				try{
					int lenEnd = chunkHeaderEnd;
					for(int j = index; j < lenEnd; j++){
						if(data[j] == ';'){
							lenEnd = j;
							break;
						}
					}
					chunkLen = Integer.parseInt(new String(data, index, lenEnd - index), 16);
				}catch(NumberFormatException e){
					throw new IOException("Invalid chunk size", e);
				}
				if(chunkLen > this.maxChunkSize)
					throw new IOException("Chunk size is larger than configured maximum: " + chunkLen + " > " + this.maxChunkSize);
				chunkHeaderEnd += EOL.length;
				int datasize = data.length - chunkHeaderEnd;
				if(datasize >= chunkLen + EOL.length){
					byte[] chunkdata = Arrays.copyOfRange(data, chunkHeaderEnd, chunkHeaderEnd + chunkLen);
					this.transformAndWriteChunk(baos, transformer, chunkdata, response);
					index = chunkHeaderEnd + chunkLen + EOL.length;
				}else{
					int write = Math.min(datasize, chunkLen);
					byte[] newChunk = new byte[chunkLen];
					System.arraycopy(data, chunkHeaderEnd, newChunk, 0, write);
					response.setAttachment("mirror_chunk", newChunk);
					response.setAttachment("mirror_chunkPointer", write);
					response.setAttachment("mirror_chunkRemaining", chunkLen + EOL.length - datasize);
					index = data.length;
				}
			}else{
				if(index > 0)
					throw new IllegalStateException("End of incomplete chunk can only be at start of packet");
				byte[] chunk = (byte[]) response.getAttachment("mirror_chunk");
				int pointer = (int) response.getAttachment("mirror_chunkPointer");
				if(remaining <= data.length){
					int write = remaining - EOL.length;
					if(write > 0)
						System.arraycopy(data, 0, chunk, pointer, write);
					this.transformAndWriteChunk(baos, transformer, chunk, response);
					response.setAttachment("mirror_chunkRemaining", 0);
					index += remaining;
					remaining = 0;
				}else{
					int write = Math.min(chunk.length - pointer, data.length);
					System.arraycopy(data, 0, chunk, pointer, write);
					response.setAttachment("mirror_chunkPointer", pointer + write);
					response.setAttachment("mirror_chunkRemaining", remaining - data.length);
					index = data.length;
				}
			}
		}
		return baos.toByteArray();
	}

	private void transformAndWriteChunk(ByteArrayOutputStream baos, Transformer transformer, byte[] data, HTTPMessage response) {
		if(data.length > 0){
			byte[] ncd = this.transform(transformer, data, response);
			if(ncd.length > 0){
				ncd = toChunk(ncd);
				baos.write(ncd, 0, ncd.length);
			}
		}else{
			baos.write(EMPTY_CHUNK, 0, EMPTY_CHUNK.length);
		}
	}

	private byte[] transform(Transformer transformer, byte[] data, HTTPMessage msg) {
		String ctype = msg.getHeader("content-type");
		if(ctype == null)
			return data;
		int ctypeEnd = ctype.indexOf(';');
		if(ctypeEnd > 0)
			ctype = ctype.substring(0, ctypeEnd);
		long start = System.nanoTime();
		byte[] n = transformer.transform(msg.getCorrespondingMessage(), ctype, data);
		if(n != null){
			long time = System.nanoTime() - start;
			logger.debug("Transformed: ", data.length, " -> ", n.length, " bytes in ", time / 1000000, ".", time % 1000000, "ms");
			return n;
		}else
			return data;
	}


	private static byte[] toChunk(byte[] data) {
		return toChunk(data, false);
	}

	private static byte[] toChunk(byte[] data, boolean includeEnd) {
		byte[] hexlen = Integer.toString(data.length, 16).getBytes();
		int chunkFrameSize = data.length + hexlen.length + EOL.length * 2;
		if(includeEnd)
			chunkFrameSize += EMPTY_CHUNK.length;
		byte[] chunk = new byte[chunkFrameSize];
		int i = 0;
		System.arraycopy(hexlen, 0, chunk, i, hexlen.length);
		i += hexlen.length;
		System.arraycopy(EOL, 0, chunk, i, EOL.length);
		i += EOL.length;
		System.arraycopy(data, 0, chunk, i, data.length);
		i += data.length;
		System.arraycopy(EOL, 0, chunk, i, EOL.length);
		i += EOL.length;
		if(includeEnd)
			System.arraycopy(EMPTY_CHUNK, 0, chunk, i, EMPTY_CHUNK.length);
		return chunk;
	}


	private static class TransformerEntry {

		public final String hostname;
		public final Transformer transformer;

		public TransformerEntry(String hostname, Transformer transformer) {
			this.hostname = hostname;
			this.transformer = transformer;
		}
	}
}
