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
package org.omegazero.proxyplugin.proxyresources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.omegazero.common.config.ConfigArray;
import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.eventbus.SubscribeEvent.Priority;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.http.common.HTTPRequest;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.ProxyUtil;

@EventBusSubscriber
public class ProxyResourcesPlugin {

	private static final Logger logger = LoggerUtil.createLogger();


	private List<Resource> resources = new ArrayList<>();

	public synchronized void configurationReload(ConfigObject config) throws IOException {
		this.resources.clear();
		ConfigArray arr = config.optArray("resources");
		if(arr == null)
			return;
		for(Object obj : arr){
			if(!(obj instanceof ConfigObject))
				throw new IllegalArgumentException("Values in 'resources' must be objects");
			ConfigObject resourceObj = (ConfigObject) obj;
			Resource resource = new Resource(resourceObj.optString("scheme", "*"), resourceObj.optString("hostname", "*"), resourceObj.getString("path"));

			Map<String, String> headers = new HashMap<>();
			ConfigObject headersObj = resourceObj.optObject("headers");
			if(headersObj != null){
				for(String key : headersObj.keySet()){
					headers.put(key, headersObj.getString(key));
				}
			}

			String contentType = resourceObj.optString("contentType", null);
			if(contentType != null)
				headers.put("content-type", contentType);

			String type = resourceObj.optString("type", null);
			if("redirect".equals(type)){
				resource.status = resourceObj.optInt("status", 307);
				headers.put("location", resourceObj.getString("location"));
			}else if("file".equals(type)){
				resource.status = resourceObj.optInt("status", 200);
				resource.data = Files.readAllBytes(Paths.get(resourceObj.getString("filepath")));
			}else if(type != null){
				throw new IllegalArgumentException("Unknown type '" + type + "'");
			}else{
				resource.status = resourceObj.optInt("status", 200);
				Object dataO = resourceObj.get("data");
				if(dataO instanceof String)
					resource.data = ((String) dataO).getBytes();
				else if(dataO instanceof ConfigArray){
					ConfigArray dataArr = (ConfigArray) dataO;
					byte[] data = new byte[dataArr.size()];
					int i = 0;
					for(Object d : dataArr)
						data[i++] = ((Number) d).byteValue();
					resource.data = data;
				}else if(dataO != null)
					throw new IllegalArgumentException("'data' must be a string or an array");
			}
			if(resource.data == null)
				resource.data = new byte[0];

			String[] headersArr = new String[headers.size() * 2];
			int headersArrI = 0;
			for(Entry<String, String> header : headers.entrySet()){
				headersArr[headersArrI++] = header.getKey();
				headersArr[headersArrI++] = header.getValue();
			}
			resource.headers = headersArr;
			this.resources.add(resource);
		}
	}


	@SubscribeEvent(priority = Priority.HIGH)
	public synchronized void onHTTPRequestPre(SocketConnection downstreamConnection, HTTPRequest request, UpstreamServer userver) {
		Resource res = this.getResource(request.getScheme(), request.getAuthority(), request.getPath());
		if(res != null){
			logger.debug("Serving resource ", res);
			request.respond(res.status, res.data, res.headers);
		}
	}

	private synchronized Resource getResource(String scheme, String hostname, String path) {
		for(Resource res : this.resources){
			if((res.scheme.equals("*") || res.scheme.equals(scheme)) && ProxyUtil.hostMatches(res.hostname, hostname) && res.path.equals(path))
				return res;
		}
		return null;
	}


	private static class Resource {

		private final String scheme;
		private final String hostname;
		private final String path;

		private int status;
		private String[] headers;
		private byte[] data;

		public Resource(String scheme, String hostname, String path) {
			this.scheme = scheme;
			this.hostname = hostname;
			this.path = path;
		}


		@Override
		public String toString() {
			return "Resource[url=" + this.scheme + "://" + this.hostname + this.path + " status=" + this.status + " length=" + this.data.length + "]";
		}
	}
}
