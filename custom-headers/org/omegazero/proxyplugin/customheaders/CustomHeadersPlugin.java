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

import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.config.ConfigArray;
import org.omegazero.proxy.config.ConfigObject;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.ProxyUtil;

@EventBusSubscriber
public class CustomHeadersPlugin {

	private static final Logger logger = LoggerUtil.createLogger();


	private final List<Host> hosts = new ArrayList<>();

	private VHostIntegration pluginVhost;

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
	public void onHTTPRequest(SocketConnection downstreamConnection, HTTPMessage request, UpstreamServer userver) {
		this.addHeaders(request, userver, true);
	}

	@SubscribeEvent
	public void onHTTPResponse(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPMessage response, UpstreamServer userver) {
		this.addHeaders(response, userver, false);
	}


	private synchronized void addHeaders(HTTPMessage msg, UpstreamServer userver, boolean up) {
		String hostname = msg.isRequest() ? msg.getAuthority() : msg.getCorrespondingMessage().getAuthority();
		if(hostname == null)
			return;
		for(Host h : this.hosts){
			if(ProxyUtil.hostMatches(h.expr, hostname)){
				this.addHeadersFromList(h.headers, msg, up);
			}
		}
		if(this.pluginVhost != null){
			List<Header> vhostHeaders = this.pluginVhost.getHostHeaders(userver);
			if(vhostHeaders != null)
				this.addHeadersFromList(vhostHeaders, msg, up);
		}
	}

	private void addHeadersFromList(List<Header> headers, HTTPMessage msg, boolean up) {
		for(Header header : headers){
			if((up && (header.direction & Header.DIRECTION_UP) == 0) || (!up && (header.direction & Header.DIRECTION_DOWN) == 0))
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
				}else if((pattern == null && val != null) || (pattern != null && val == null)){
					if(val != null){
						rhe = false;
						break;
					}
				}else if(!pattern.matcher(val).matches()){
					rhe = false;
					break;
				}
			}
			if(!rhe)
				continue;
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
			ConfigObject reqObj = headerObj.optObject("requiredHeaders");
			if(reqObj != null){
				for(String rhkey : reqObj.keySet()){
					String v = reqObj.getString(rhkey);
					header.requiredHeaders.put(rhkey.toLowerCase(), v != null ? Pattern.compile(v) : null);
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
		private Map<String, Pattern> requiredHeaders = new HashMap<>();

		public Header(String key, String value, int direction, int mode) {
			this.key = key;
			this.value = value;
			this.direction = direction;
			this.mode = mode;
		}
	}
}
