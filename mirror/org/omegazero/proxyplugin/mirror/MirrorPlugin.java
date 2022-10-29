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

import java.util.ArrayList;
import java.util.List;

import org.omegazero.common.config.ConfigArray;
import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.eventbus.SubscribeEvent.Priority;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.plugins.ExtendedPluginConfiguration;
import org.omegazero.http.common.HTTPResponse;
import org.omegazero.http.common.HTTPResponseData;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http.ProxyHTTPRequest;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.ProxyUtil;
import org.omegazero.proxyplugin.mirror.transformer.AuthorityTransformer;
import org.omegazero.proxyplugin.mirror.transformer.PathTransformer;

@EventBusSubscriber
public class MirrorPlugin {

	private static final Logger logger = Logger.create();


	private List<TransformerEntry> transformers = new ArrayList<>();

	@ExtendedPluginConfiguration
	public synchronized void configurationReload(ConfigObject config) {
		this.transformers.clear();
		ConfigArray arr = config.optArray("transformers");
		if(arr == null)
			return;
		for(Object obj : arr){
			if(!(obj instanceof ConfigObject))
				continue;
			ConfigObject tObj = (ConfigObject) obj;
			String type = tObj.getString("type");
			TransformerReplacements replacements = TransformerReplacements.from(tObj.getObject("replacements"));
			boolean toHttp = tObj.optBoolean("toHttp", false);
			Transformer t;
			if(type.equals("authority")){
				t = new AuthorityTransformer(replacements, toHttp);
			}else if(type.equals("path")){
				t = new PathTransformer(replacements, toHttp);
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
	public void onHTTPResponse(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPResponse response, UpstreamServer upstreamServer) {
		String reqHostname = ((ProxyHTTPRequest) response.getOther()).getInitialAuthority();
		if(reqHostname == null)
			return;
		Transformer transformer = null;
		synchronized(this){
			for(TransformerEntry te : this.transformers){
				if(ProxyUtil.hostMatches(te.hostname, reqHostname)){
					transformer = te.transformer;
					break;
				}
			}
		}
		if(transformer == null)
			return;
		response.setAttachment("mirror_transformer", transformer);
		response.setChunkedTransfer(true);
	}

	@SubscribeEvent(priority = Priority.HIGH)
	public void onHTTPResponseData(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPResponseData responsedata, UpstreamServer upstreamServer) {
		responsedata.setData(this.processDataChunk(responsedata.getHttpMessage(), responsedata.getData()));
	}


	private byte[] processDataChunk(HTTPResponse response, byte[] data) {
		Transformer transformer = (Transformer) response.getAttachment("mirror_transformer");
		if(transformer == null)
			return data;
		return this.transform(transformer, data, response);
	}

	private byte[] transform(Transformer transformer, byte[] data, HTTPResponse msg) {
		String ctype = msg.getHeader("content-type");
		if(ctype == null)
			return data;
		int ctypeEnd = ctype.indexOf(';');
		if(ctypeEnd > 0)
			ctype = ctype.substring(0, ctypeEnd);
		long start = System.nanoTime();
		byte[] n = transformer.transform((ProxyHTTPRequest) msg.getOther(), ctype, data);
		if(n != null){
			long time = System.nanoTime() - start;
			if(logger.debug())
				logger.debug("Transformed: ", data.length, " -> ", n.length, " bytes in ", time / 1000000, ".", String.format("%06d", time % 1000000), "ms");
			return n;
		}else
			return data;
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
