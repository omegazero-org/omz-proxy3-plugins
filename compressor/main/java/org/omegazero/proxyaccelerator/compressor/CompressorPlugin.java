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
package org.omegazero.proxyaccelerator.compressor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.omegazero.common.config.ConfigArray;
import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.config.ConfigurationOption;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.eventbus.SubscribeEvent.Priority;
import org.omegazero.common.logging.Logger;
import org.omegazero.http.common.HTTPRequest;
import org.omegazero.http.common.HTTPResponse;
import org.omegazero.http.common.HTTPResponseData;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxyaccelerator.cache.CachePlugin;
import org.omegazero.proxyaccelerator.cache.VaryComparator;

@EventBusSubscriber
public class CompressorPlugin {

	private static final Logger logger = Logger.create();

	private static Map<String, Supplier<Compressor>> compressors = new ConcurrentHashMap<>();


	@ConfigurationOption
	private List<String> enabledMimeTypes = new ArrayList<>();
	@ConfigurationOption
	private String preferredCompressor = null;
	@ConfigurationOption
	private boolean onlyIfChunked = false;
	@ConfigurationOption
	private boolean onlyIfNoEncoding = true;


	@SubscribeEvent
	public void onInit() {
		if(Proxy.getInstance().isPluginLoaded("cache"))
			CachePlugin.registerVaryComparator("accept-encoding", VaryComparator.DIRECTIVE_LIST_COMPARATOR);
	}

	@SubscribeEvent(priority = Priority.LOW)
	public void onHTTPResponse(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPResponse response, UpstreamServer upstreamServer)
			throws IOException {
		if(!response.isChunkedTransfer() && this.onlyIfChunked)
			return;
		if(response.headerExists("content-encoding") && this.onlyIfNoEncoding)
			return;

		String cacheControl = response.getHeader("cache-control");
		if(cacheControl != null && cacheControl.contains("no-transform"))
			return;

		String ctype = response.getHeader("content-type");
		if(ctype == null)
			return;
		int ctypeEnd = ctype.indexOf(';');
		if(ctypeEnd > 0)
			ctype = ctype.substring(0, ctypeEnd);
		if(!this.isMimeTypeEnabled(ctype))
			return;

		HTTPRequest request = response.getOther();
		String acceptEncoding = request.getHeader("accept-encoding");
		if(acceptEncoding == null)
			return;
		String[] encodings = acceptEncoding.split(",");
		String availableEncoding = null;
		String selectedEncoding = null;
		for(int i = 0; i < encodings.length; i++){
			String enc = encodings[i].trim();
			int pind = enc.indexOf(';');
			if(pind > 0)
				enc = enc.substring(0, pind).trim();
			if(availableEncoding == null && CompressorPlugin.compressors.containsKey(enc))
				availableEncoding = enc;
			if(enc.equals(this.preferredCompressor)){
				selectedEncoding = enc;
				break;
			}
		}
		if(selectedEncoding == null){
			if(availableEncoding == null)
				return;
			selectedEncoding = availableEncoding;
		}

		Supplier<Compressor> compressorSupplier = CompressorPlugin.compressors.get(selectedEncoding);
		if(compressorSupplier == null){
			logger.debug("No compressor found for selected encoding '", selectedEncoding, "'");
			return;
		}
		Compressor compressor = compressorSupplier.get();
		logger.debug("Using compressor '", selectedEncoding, "' (", compressor.getClass().getName(), ")");
		compressor.init();
		response.appendHeader("content-encoding", selectedEncoding, ", ");
		response.appendHeader("vary", "accept-encoding", ", ");
		response.setAttachment("compressor_instance", compressor);
		response.setChunkedTransfer(true);
	}

	@SubscribeEvent(priority = Priority.LOW)
	public void onHTTPResponseData(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPResponseData responsedata, UpstreamServer upstreamServer)
			throws IOException {
		Compressor compressor = (Compressor) responsedata.getHttpMessage().getAttachment("compressor_instance");
		if(compressor == null)
			return;
		byte[] data = responsedata.getData();
		byte[] compressed = compressor.compressPart(data, data.length, responsedata.isLastPacket());
		logger.trace("Compressed response data: ", data.length, " -> ", compressed.length);
		responsedata.setData(compressed);
	}


	private synchronized boolean isMimeTypeEnabled(String mt) {
		return this.enabledMimeTypes.contains(mt);
	}


	public static boolean registerCompressor(String name, Supplier<Compressor> compressor) {
		return CompressorPlugin.compressors.put(name, compressor) != null;
	}


	static{
		CompressorPlugin.registerCompressor("deflate", () -> {
			return new DeflateCompressor();
		});
		CompressorPlugin.registerCompressor("gzip", () -> {
			return new GZIPCompressor();
		});
	}
}
