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
package org.omegazero.proxyplugin.customheaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.omegazero.common.config.ConfigArray;
import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.plugins.ExtendedPluginConfiguration;
import org.omegazero.http.common.HTTPMessage;
import org.omegazero.http.common.HTTPRequest;
import org.omegazero.http.common.HTTPResponse;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.ProxyUtil;

@EventBusSubscriber
public class CustomHeadersPlugin {

	private static final Logger logger = Logger.create();


	private final List<Host> hosts = new ArrayList<>();

	private VHostIntegration pluginVhost;

	@ExtendedPluginConfiguration
	public synchronized void configurationReload(ConfigObject config) {
		this.hosts.clear();
		for(String hostname : config.keySet()){
			ConfigArray headers = config.getArray(hostname);
			Host host = new Host(hostname, fromConfigArray(headers));
			this.hosts.add(host);
		}

		if(this.pluginVhost == null && Proxy.getInstance().isPluginLoaded("vhost")){
			logger.debug("Detected that vhost is loaded");
			this.pluginVhost = new VHostIntegration();
		}else if(this.pluginVhost != null)
			this.pluginVhost.invalidate();
	}


	@SubscribeEvent
	public void onHTTPRequest(SocketConnection downstreamConnection, HTTPRequest request, UpstreamServer userver) {
		this.addHeaders(request, request.getAuthority(), request.getPath(), userver, true);
	}

	@SubscribeEvent
	public void onHTTPResponse(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPResponse response, UpstreamServer userver) {
		HTTPRequest request = response.getOther();
		this.addHeaders(response, request.getAuthority(), request.getPath(), userver, false);
	}


	private synchronized void addHeaders(HTTPMessage msg, String hostname, String requestPath, UpstreamServer userver, boolean up) {
		if(hostname == null)
			return;

		if(this.pluginVhost != null){
			List<Header> vhostHeaders = this.pluginVhost.getHostHeaders(userver);
			if(vhostHeaders != null)
				this.addHeadersFromList(requestPath, vhostHeaders, msg, up);
		}

		for(Host h : this.hosts){
			if(ProxyUtil.hostMatches(h.expr, hostname)){
				this.addHeadersFromList(requestPath, h.headers, msg, up);
			}
		}
	}

	private void addHeadersFromList(String requestPath, List<Header> headers, HTTPMessage msg, boolean up) {
		for(Header header : headers){
			if((up && (header.direction & Header.DIRECTION_UP) == 0) || (!up && (header.direction & Header.DIRECTION_DOWN) == 0))
				continue;
			if(!(header.requestPath == null || (header.requestPath.matcher(requestPath).matches() ^ header.requestPathBlacklist)))
				continue;

			String prevVal = msg.getHeader(header.key);
			if(prevVal == null && (header.mode & Header.MODE_IF_EXIST) != 0)
				continue;
			boolean rhe = true;
			for(Entry<String, Pattern> rh : header.requiredHeaders.entrySet()){
				String val = msg.getHeader(rh.getKey());
				Pattern pattern = rh.getValue();
				if(pattern == null && val == null){
					continue;
				}else if((pattern == null && val != null) || (pattern != null && val == null) || !pattern.matcher(val).matches()){
					rhe = false;
					break;
				}
			}
			if(!rhe)
				continue;

			if(!up && header.requiredStatus != null){
				int status = ((HTTPResponse) msg).getStatus();
				boolean rstatus = false;
				for(int s : header.requiredStatus){
					if(s == status){
						rstatus = true;
						break;
					}
				}
				if(rstatus != header.requiredStatusWhitelist)
					continue;
			}

			if((header.mode & Header.MODE_KEEP) != 0){
				if(prevVal == null){
					msg.setHeader(header.key, header.value);
				}
			}else if((header.mode & Header.MODE_REPLACE) != 0)
				msg.setHeader(header.key, header.value);
			else if((header.mode & Header.MODE_APPEND) != 0)
				msg.setHeader(header.key, prevVal != null ? (prevVal + header.separator + header.value) : header.value);
			else if((header.mode & Header.MODE_PREPEND) != 0)
				msg.setHeader(header.key, prevVal != null ? (header.value + header.separator + prevVal) : header.value);
			else
				throw new RuntimeException("Unknown flags: " + header.mode);
		}
	}


	public static List<Header> fromConfigArray(ConfigArray arr) {
		List<Header> headers = new ArrayList<>();
		for(Object obj : arr){
			if(!(obj instanceof ConfigObject))
				throw new IllegalArgumentException("Values in customheaders array must be objects");
			ConfigObject headerObj = (ConfigObject) obj;
			int direction = resolveDirection(headerObj.getString("direction"));
			int mode = resolveMode(headerObj.optString("mode", "keep"));
			String value = headerObj.getString("value");
			Header header = new Header(headerObj.getString("key").toLowerCase(), value.length() > 0 ? value : null, direction, mode);

			if((header.mode & (Header.MODE_APPEND | Header.MODE_PREPEND)) != 0)
				header.separator = headerObj.getString("separator");

			String requestPath = headerObj.optString("requestPath", null);
			if(requestPath != null){
				if(requestPath.startsWith("!")){
					requestPath = requestPath.substring(1);
					header.requestPathBlacklist = true;
				}
				header.requestPath = Pattern.compile(requestPath);
			}

			Object reqStatus = headerObj.get("requiredStatus");
			if(reqStatus instanceof ConfigArray){
				header.requiredStatus = new int[((ConfigArray) reqStatus).size()];
				int i = 0;
				for(Object s : (ConfigArray) reqStatus){
					if(!(s instanceof Integer))
						throw new IllegalArgumentException("Values in 'requiredStatus' must be numbers");
					header.requiredStatus[i++] = (Integer) s;
				}
			}else if(reqStatus instanceof Integer){
				header.requiredStatus = new int[] { (Integer) reqStatus };
			}else if(reqStatus != null)
				throw new IllegalArgumentException("'requiredStatus' must be a number or an array of numbers");
			header.requiredStatusWhitelist = headerObj.optBoolean("requiredStatusWhitelist", true);

			ConfigObject reqObj = headerObj.optObject("requiredHeaders");
			if(reqObj != null){
				for(String rhkey : reqObj.keySet()){
					Object v = reqObj.get(rhkey);
					if(!(v == null || v instanceof String))
						throw new IllegalArgumentException("Values in 'requiredHeaders' must be strings or null");
					header.requiredHeaders.put(rhkey.toLowerCase(), v != null ? Pattern.compile((String) v) : null);
				}
			}
			headers.add(header);
		}
		return headers;
	}

	private static int resolveDirection(String val) {
		switch(val){
			case "request":
				return Header.DIRECTION_UP;
			case "response":
				return Header.DIRECTION_DOWN;
			case "both":
				return Header.DIRECTION_UP | Header.DIRECTION_DOWN;
			default:
				throw new IllegalArgumentException("Invalid value for 'direction'");
		}
	}

	private static int resolveMode(String val) {
		switch(val){
			case "keep":
				return Header.MODE_KEEP;
			case "replace":
				return Header.MODE_REPLACE;
			case "append":
				return Header.MODE_APPEND;
			case "prepend":
				return Header.MODE_PREPEND;
			case "replaceIfExist":
				return Header.MODE_REPLACE | Header.MODE_IF_EXIST;
			case "appendIfExist":
				return Header.MODE_APPEND | Header.MODE_IF_EXIST;
			case "prependIfExist":
				return Header.MODE_PREPEND | Header.MODE_IF_EXIST;
			default:
				throw new IllegalArgumentException("Invalid value for 'mode'");
		}
	}


	private static class Host {

		private final String expr;
		private final List<Header> headers;

		public Host(String expr, List<Header> headers) {
			this.expr = expr;
			this.headers = headers;
		}
	}

	protected static class Header {

		private static final int DIRECTION_UP = 1;
		private static final int DIRECTION_DOWN = 2;

		private static final int MODE_IF_EXIST = 1;
		private static final int MODE_KEEP = 2;
		private static final int MODE_REPLACE = 4;
		private static final int MODE_APPEND = 8;
		private static final int MODE_PREPEND = 16;

		private final String key;
		private final String value;
		private final int direction;
		private final int mode;

		private String separator;
		private Pattern requestPath;
		private boolean requestPathBlacklist = false;
		private int[] requiredStatus = null;
		private boolean requiredStatusWhitelist = false;
		private Map<String, Pattern> requiredHeaders = new HashMap<>();

		public Header(String key, String value, int direction, int mode) {
			this.key = key;
			this.value = value;
			this.direction = direction;
			this.mode = mode;
		}
	}
}
