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
package org.omegazero.proxyaccelerator.cache;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.omegazero.common.event.Tasks;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.eventbus.SubscribeEvent.Priority;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.config.ConfigObject;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.http.HTTPCommon;
import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxy.http.HTTPMessageData;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxyaccelerator.cache.impl.LRUCache;
import org.omegazero.proxyaccelerator.cache.impl.SoftReferenceCache;
import org.omegazero.proxyaccelerator.cache.integration.VHostIntegration;

@EventBusSubscriber
public class CachePlugin {

	private static final Logger logger = LoggerUtil.createLogger();

	private static Map<String, Supplier<ResourceCache>> cacheTypes = new HashMap<>();


	private final Map<HTTPMessage, PendingCacheEntry> pendingCacheEntries = new HashMap<>();

	private CacheConfig cacheConfig;
	private VHostIntegration pluginVhost;

	private String cacheName;
	private boolean appendCacheName;
	private String cacheServedByPrefix;
	private String cacheType;
	private long cacheLimit;

	private ResourceCache cache;


	public synchronized void configurationReload(ConfigObject config) {
		this.cacheConfig = CacheConfig.from(config, null);

		if(this.pluginVhost == null && Proxy.getInstance().isPluginLoaded("vhost")){
			logger.debug("Detected that vhost is loaded");
			this.pluginVhost = new VHostIntegration();
		}else if(this.pluginVhost != null)
			this.pluginVhost.invalidate();

		this.cacheName = config.optString("name", null);
		this.appendCacheName = config.optBoolean("appendCacheName", false);
		this.cacheServedByPrefix = config.optString("servedByPrefix", "cache-");
		this.cacheType = config.optString("type", "lru");
		this.cacheLimit = config.optLong("sizeLimit", (long) (Runtime.getRuntime().maxMemory() * 0.5f));

		if(this.cache != null) // dont create cache on plugin init (done in onInit instead)
			this.reloadCache();
	}


	@SubscribeEvent
	public void onPreinit() {
		if(this.appendCacheName)
			Proxy.getInstance().setInstanceNameAppendage(this.cacheName);
	}

	@SubscribeEvent
	public void onInit() {
		this.reloadCache();

		Tasks.interval((a) -> {
			CachePlugin.this.cleanup();
		}, 60000).daemon();
	}

	@SubscribeEvent
	public void onHTTPRequestPre(SocketConnection downstreamConnection, HTTPMessage request, UpstreamServer userver) {
		if(request.getAuthority() == null)
			return;
		if(request.getMethod().equals("PURGE")){
			CacheConfig cc = this.getConfigForRequest(request);
			CacheConfig.CacheConfigOverride cco = cc.getOverride(request);
			if(cco == null)
				return;
			String purgeKey = cco.getPurgeKey();
			if(purgeKey == null){ // disabled
				if(!cco.isPropagatePurgeRequest())
					this.purgeReply(request, HTTPCommon.STATUS_METHOD_NOT_ALLOWED, "disabled");
			}else if(purgeKey.length() > 0 && !purgeKey.equals(request.getHeader("x-purge-key"))){
				this.purgeReply(request, HTTPCommon.STATUS_UNAUTHORIZED, "unauthorized");
			}else{
				String purgeMethod = request.getHeader("x-purge-method");
				if(purgeMethod == null)
					purgeMethod = "GET";
				String key = CachePlugin.getCacheKey(purgeMethod, request.getScheme(), request.getAuthority(), request.getOrigPath());
				CacheEntry entry = this.cache.delete(key);
				if(entry != null){
					logger.debug("Purged cache entry '", key, "' (age ", entry.age(), ")");
					this.purgeReply(request, HTTPCommon.STATUS_OK, "ok");
				}else if(!cco.isPropagatePurgeRequest()){
					this.purgeReply(request, HTTPCommon.STATUS_NOT_FOUND, "nonexistent");
				}
			}
		}else{
			String key = CachePlugin.getCacheKey(request);
			CacheEntry entry = this.cache.fetch(key);
			if(entry != null && entry.isUsableFor(request)){
				CacheConfig cc = this.getConfigForRequest(request);
				if(!cc.isUsable(request, entry))
					return;
				HTTPMessage res = entry.getResponse().clone();
				entry.incrementHits();

				boolean etagCondition = true;
				String resourceTag = res.getHeader("etag");
				String inm = request.getHeader("if-none-match");
				if(resourceTag != null && inm != null){
					if(inm.equals("*")){
						etagCondition = false;
					}else{
						if(resourceTag.startsWith("W/"))
							resourceTag = resourceTag.substring(2);
						String[] etags = inm.split(",");
						for(String etag : etags){
							etag = etag.trim();
							if(etag.startsWith("W/"))
								etag = etag.substring(2);
							if(etag.equals(resourceTag)){
								etagCondition = false;
								break;
							}
						}
					}
				}

				if(!etagCondition){
					res.setData(new byte[0]);
					res.setStatus(HTTPCommon.STATUS_NOT_MODIFIED);
					res.deleteHeader("content-length");
				}

				this.addHeaders(res, entry, true);
				request.getEngine().respond(request, res);
			}
		}
	}

	@SubscribeEvent(priority = Priority.LOWEST) // lowest to allow other plugins to edit the response before caching
	public void onHTTPResponse(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPMessage response, UpstreamServer upstreamServer) {
		String key = CachePlugin.getCacheKey(response.getCorrespondingMessage());
		CacheEntry entry = this.cache.fetch(key);
		// if the entry already exists, it will be replaced if this response finishes

		this.tryStartCachingResponse(upstreamConnection, response, upstreamServer, key);

		this.addHeaders(response, entry, false);
	}

	@SubscribeEvent(priority = Priority.LOWEST)
	public void onHTTPResponseData(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPMessageData responsedata, UpstreamServer upstreamServer) {
		HTTPMessage response = responsedata.getHttpMessage();
		synchronized(this.pendingCacheEntries){
			PendingCacheEntry pce = this.pendingCacheEntries.get(response);
			if(pce != null){
				pce.addData(responsedata.getData());
				this.tryCompleteCacheEntry(pce, response);
			}
		}
	}


	private void purgeReply(HTTPMessage request, int status, String statusmsg) {
		request.getEngine().respond(request, status,
				("{\"status\":\"" + statusmsg + "\"" + (this.cacheName != null ? (",\"server\":\"" + this.cacheServedByPrefix + this.cacheName + "\"") : "") + "}").getBytes(),
				"content-type", "application/json");
	}

	private void tryStartCachingResponse(SocketConnection upstreamConnection, HTTPMessage response, UpstreamServer upstreamServer, String key) {
		long length;
		if(response.getCorrespondingMessage().getMethod().equals("HEAD")){
			length = 0;
		}else{
			length = CachePlugin.parseIntSafe(response.getHeader("content-length"), -1);
		}
		if(length < 0)
			return;
		CacheConfig cc = this.getConfig(upstreamServer);
		CacheEntry.Properties properties = cc.getResourceProperties(response, length);
		if(properties != null){
			synchronized(this.pendingCacheEntries){
				for(PendingCacheEntry p : this.pendingCacheEntries.values()){
					if(p.key.equals(key)) // there is already a pending entry for this key
						return;
				}
				PendingCacheEntry pce = new PendingCacheEntry(upstreamConnection, response, (int) length, properties);
				pce.addData(response.getData());
				if(!this.tryCompleteCacheEntry(pce, response))
					this.pendingCacheEntries.put(response, pce);
			}
		}
	}

	private boolean tryCompleteCacheEntry(PendingCacheEntry pce, HTTPMessage response) {
		synchronized(this.pendingCacheEntries){
			if(pce.dataLen > pce.expectedContentSize){
				logger.warn("Received more data than expected for URL '", pce.request.requestURI(), "', discarding cache entry: ", pce.dataLen, " > ",
						pce.expectedContentSize);
				this.pendingCacheEntries.remove(response);
			}else if(pce.dataLen == pce.expectedContentSize){
				String key = pce.key;
				logger.debug("Caching resource '", key, "' with maxAge ", pce.ceProperties.getMaxAge(), " (", pce.dataLen, " bytes)");
				this.pendingCacheEntries.remove(response);
				this.cache.store(key, pce.get());
			}else
				return false;
			return true;
		}
	}

	private void addHeaders(HTTPMessage msg, CacheEntry entry, boolean hit) {
		if(hit)
			msg.setHeader("age", String.valueOf(entry.age()));
		else if(!msg.headerExists("age"))
			msg.setHeader("age", "0");
		msg.appendHeader("x-cache", hit ? "HIT" : "MISS", ", ");
		msg.appendHeader("x-cache-lookup", entry != null ? "HIT" : "MISS", ", ");
		msg.appendHeader("x-cache-hits", entry != null ? String.valueOf(entry.getHits()) : "0", ", ");
		if(this.cacheName != null)
			msg.appendHeader("x-served-by", this.cacheServedByPrefix + this.cacheName, ", ");
	}

	private CacheConfig getConfigForRequest(HTTPMessage request) {
		UpstreamServer userver = Proxy.getInstance().getUpstreamServer(request.getAuthority(), request.getOrigPath());
		if(userver == null)
			return this.cacheConfig;
		CacheConfig cc = this.getConfig(userver);
		return cc;
	}

	private CacheConfig getConfig(UpstreamServer userver) {
		CacheConfig cc = null;
		if(this.pluginVhost != null){
			cc = this.pluginVhost.getConfigOverride(userver, this.cacheConfig);
		}
		if(cc == null)
			cc = this.cacheConfig;
		return cc;
	}

	private void cleanup() {
		synchronized(this.pendingCacheEntries){
			Iterator<java.util.Map.Entry<HTTPMessage, PendingCacheEntry>> iterator = this.pendingCacheEntries.entrySet().iterator();
			while(iterator.hasNext()){
				PendingCacheEntry entry = iterator.next().getValue();
				if(!entry.upstreamConnection.isConnected()){
					logger.warn("Removing pending cache entry with closed upstream connection (the connection closed before the full response was received): ",
							entry.request.requestURI());
					iterator.remove();
				}else if(entry.getPendingTime() > 60){
					logger.warn("Removing cache entry that was pending for more than 60 seconds: ", entry.request.requestURI());
					iterator.remove();
				}
			}
		}
		// the cache cleanup method removes stale entries
		this.cache.cleanup();
	}

	private synchronized void reloadCache() {
		if(this.cache != null)
			this.cache.close();

		Supplier<ResourceCache> supplier = CachePlugin.cacheTypes.get(this.cacheType);
		if(supplier == null)
			throw new IllegalArgumentException("Invalid cache type '" + this.cacheType + "'");
		this.cache = supplier.get();
		this.cache.setMaxCacheSize(this.cacheLimit);

		logger.debug("Initialized cache type ", this.cache.getClass().getName());
	}


	private static String getCacheKey(HTTPMessage request) {
		return CachePlugin.getCacheKey(request.getMethod(), request.getScheme(), request.getAuthority(), request.getOrigPath());
	}

	private static String getCacheKey(String method, String scheme, String authority, String path) {
		return method + " " + scheme + "://" + authority + path;
	}

	/**
	 * Registers a new implementation of {@link ResourceCache}.
	 * 
	 * @param name     The name of this implementation used in the configuration file
	 * @param supplier Generator for new instances of the cache
	 * @return <code>true</code> if the implementation was registered successfully, <code>false</code> if an implementation with the given name already exists
	 */
	public static synchronized boolean registerCacheImplementation(String name, Supplier<ResourceCache> supplier) {
		if(CachePlugin.cacheTypes.containsKey(name))
			return false;
		CachePlugin.cacheTypes.put(name, supplier);
		return true;
	}

	/**
	 * Parses the given string as a positive integer. The string must only contain digits. If the string is <code>null</code>, contains invalid characters or the resulting
	 * number would be larger than <code>Integer.MAX_VALUE</code>, the value passed to <b>def</b> is returned.
	 * 
	 * @param str The string to parse
	 * @param def The value to return if the string could not be parsed
	 * @return The parsed integer or <b>def</b> if the string does not represent a positive 31-bit integer
	 */
	public static int parseIntSafe(String str, int def) {
		if(str == null)
			return def;
		str = str.trim();
		int result = 0;
		int len = str.length();
		int i = 0;
		while(i < len){
			char c = str.charAt(i++);
			if(c < '0' || c > '9')
				return def;
			result *= 10;
			result += c - 48;
			if(result < 0) // overflow
				return def;
		}
		return result;
	}

	public static long time() {
		return System.nanoTime() / 1000000;
	}


	private static class PendingCacheEntry {

		private final SocketConnection upstreamConnection;
		private final HTTPMessage response;
		private final int expectedContentSize;
		private final CacheEntry.Properties ceProperties;

		private final HTTPMessage request;
		private final String key;
		private final long created = time();

		private List<byte[]> data = new LinkedList<>();
		private int dataLen = 0;

		public PendingCacheEntry(SocketConnection upstreamConnection, HTTPMessage response, int expectedContentSize, CacheEntry.Properties properties) {
			this.upstreamConnection = upstreamConnection;
			this.response = response.clone();
			this.expectedContentSize = expectedContentSize;
			this.ceProperties = properties;

			this.response.setData(null);
			HTTPMessage request = this.response.getCorrespondingMessage();
			if(request == null)
				throw new NullPointerException("request is null");
			request = request.clone(); // may still be used so clone before editing
			request.setAuthority(request.getOrigAuthority()); // reset any changes
			request.setPath(request.getOrigPath());
			request.setData(null); // data is unused
			this.request = request;
			this.key = CachePlugin.getCacheKey(this.request);

			if(!this.response.headerExists("date"))
				this.response.setHeader("date", HTTPCommon.dateString());
		}


		public int getPendingTime() {
			return (int) ((time() - this.created) / 1000);
		}

		public int getResponseDelay() {
			return (int) ((this.request.getCreatedTime() - this.response.getCreatedTime()) / 1000);
		}


		public synchronized void addData(byte[] d) {
			this.data.add(d);
			this.dataLen += d.length;
		}

		public synchronized CacheEntry get() {
			if(this.data == null)
				throw new IllegalStateException("Already created");
			byte[] data = new byte[this.dataLen];
			int i = 0;
			for(byte[] d : this.data){
				System.arraycopy(d, 0, data, i, d.length);
				i += d.length;
			}
			this.data = null;
			this.response.setData(data);

			int correctedAgeValue = CachePlugin.parseIntSafe(this.response.getHeader("age"), 0) + this.getResponseDelay();
			return new CacheEntry(this.request, this.response, time() + (this.ceProperties.getMaxAge() - correctedAgeValue) * 1000L, correctedAgeValue, this.ceProperties);
		}
	}


	static{
		CachePlugin.registerCacheImplementation("lru", () -> {
			return new LRUCache();
		});
		CachePlugin.registerCacheImplementation("softreference", () -> {
			return new SoftReferenceCache();
		});
	}
}
