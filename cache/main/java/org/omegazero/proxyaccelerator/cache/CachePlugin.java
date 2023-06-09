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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.omegazero.common.config.ConfigArray;
import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.config.ConfigurationOption;
import org.omegazero.common.event.Tasks;
import org.omegazero.common.eventbus.Event;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.eventbus.SubscribeEvent.Priority;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.plugins.ExtendedPluginConfiguration;
import org.omegazero.http.common.HTTPMessage;
import org.omegazero.http.common.HTTPResponse;
import org.omegazero.http.common.HTTPResponseData;
import org.omegazero.http.util.HTTPStatus;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.http.HTTPCommon;
import org.omegazero.proxy.http.ProxyHTTPRequest;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxyaccelerator.cache.integration.VHostIntegration;

@EventBusSubscriber
public class CachePlugin {

	private static final Logger logger = Logger.create();

	private static Map<String, Function<? super ConfigObject, ? extends ResourceCache>> cacheTypes = new ConcurrentHashMap<>();
	private static Map<String, VaryComparator> varyComparators = new ConcurrentHashMap<>();

	public static final Event EVENT_CACHE_HIT = new Event("cache_hit", new Class<?>[] { ProxyHTTPRequest.class, HTTPResponseData.class });
	public static final Event EVENT_CACHE_MISS = new Event("cache_miss", new Class<?>[] { ProxyHTTPRequest.class });
	public static final Event EVENT_CACHE_PURGE = new Event("cache_purge", new Class<?>[] { ProxyHTTPRequest.class });
	public static final Event EVENT_CACHE_CANDIDATE = new Event("cache_candidate", new Class<?>[] { HTTPResponse.class, CacheEntry.Properties.class });
	public static final Event EVENT_CACHE_STORE = new Event("cache_store", new Class<?>[] { CacheEntry.class });


	private final Map<HTTPResponse, PendingCacheEntry> pendingCacheEntries = new HashMap<>();

	private CacheConfig cacheConfig;
	private VHostIntegration pluginVhost;

	@ConfigurationOption
	private String name = null;
	@ConfigurationOption
	private boolean appendCacheName = false;
	@ConfigurationOption
	private String servedByPrefix = "cache-";
	@ConfigurationOption
	private ConfigArray caches = null;
	@ConfigurationOption
	private boolean enableServeStale = true;

	private ConfigObject singleCacheConfig;
	private ResourceCache cache;


	@ExtendedPluginConfiguration
	public synchronized void configurationReload(ConfigObject config) {
		this.cacheConfig = CacheConfig.from(config, null);

		if(this.pluginVhost == null && Proxy.getInstance().isPluginLoaded("vhost")){
			logger.debug("Detected that vhost is loaded");
			this.pluginVhost = new VHostIntegration();
		}else if(this.pluginVhost != null)
			this.pluginVhost.invalidate();

		if(config.optString("type", null) != null)
			this.singleCacheConfig = config;
		else
			this.singleCacheConfig = new ConfigObject();

		if(this.cache != null) // dont create cache on plugin init (done in onInit instead)
			this.reloadCache();
	}


	@SubscribeEvent
	public void onPreinit() {
		if(this.appendCacheName)
			Proxy.getInstance().setInstanceNameAppendage(this.name);
	}

	@SubscribeEvent
	public void onInit() {
		this.reloadCache();

		Tasks.I.interval(this::cleanup, 60000).daemon();
	}

	@SubscribeEvent
	public void onHTTPRequestPre(SocketConnection downstreamConnection, ProxyHTTPRequest request, UpstreamServer userver) {
		if(request.getAuthority() == null)
			return;
		if(request.getMethod().equals("PURGE")){
			Proxy.getInstance().dispatchEvent(EVENT_CACHE_PURGE, request);
			CacheConfig cc = this.getConfig(userver);
			CacheConfig.CacheConfigOverride cco = cc.getOverride(request);
			if(cco == null)
				return;
			String purgeKey = cco.purgeKey;
			if(purgeKey == null){ // disabled
				if(!cco.propagatePurgeRequest)
					this.purgeReply(request, HTTPStatus.STATUS_METHOD_NOT_ALLOWED, "disabled", null);
			}else if(purgeKey.length() > 0 && !purgeKey.equals(request.getHeader("x-purge-key"))){
				this.purgeReply(request, HTTPStatus.STATUS_UNAUTHORIZED, "unauthorized", null);
			}else{
				String purgeMethod = request.getHeader("x-purge-method");
				if(purgeMethod == null)
					purgeMethod = "GET";
				String path = request.getInitialPath();
				if(cco.wildcardPurgeEnabled && path.endsWith("**")){
					String keyPrefix = CachePlugin.getCacheKey(purgeMethod, request.getScheme(), request.getAuthority(), path.substring(0, path.length() - 2));
					int deleted = this.cache.deleteIfKey((s) -> {
						return s.startsWith(keyPrefix);
					});
					if(deleted < 0){
						this.purgeReply(request, HTTPStatus.STATUS_NOT_IMPLEMENTED, "unsupported", null);
					}else{
						logger.debug("Purged ", deleted, " cache entries (wildcard key: '", keyPrefix, "')");
						this.purgeReply(request, HTTPStatus.STATUS_OK, "ok", ",\"deleted\":" + deleted);
					}
				}else{
					String key = CachePlugin.getCacheKey(purgeMethod, request.getScheme(), request.getAuthority(), path);
					CacheEntry entry = this.cache.delete(key);
					if(entry != null){
						logger.debug("Purged cache entry '", key, "' (age ", entry.age(), ")");
						this.purgeReply(request, HTTPStatus.STATUS_OK, "ok", null);
					}else if(!cco.propagatePurgeRequest){
						this.purgeReply(request, HTTPStatus.STATUS_NOT_FOUND, "nonexistent", null);
					}
				}
			}
		}else{
			this.serveFromCache(request, false);
		}
	}

	@SubscribeEvent(priority = Priority.LOWEST) // lowest to allow other plugins to edit the response before caching
	public void onHTTPResponse(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPResponse response, UpstreamServer upstreamServer) {
		String key = CachePlugin.getCacheKey((ProxyHTTPRequest) response.getOther());
		CacheEntry entry = this.cache.fetch(key);
		// if the entry already exists, it will be replaced if this response finishes

		boolean cacheable = this.tryStartCachingResponse(upstreamConnection, response, upstreamServer, key);
		if(!cacheable && entry != null) // response is not cacheable, remove the cache entry (likely cache-control changed since response was cached)
			this.cache.delete(key);

		this.addHeaders(response, entry, false);
	}

	@SubscribeEvent(priority = Priority.LOWEST)
	public void onHTTPResponseData(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPResponseData responsedata, UpstreamServer upstreamServer) {
		HTTPResponse response = responsedata.getHttpMessage();
		synchronized(this.pendingCacheEntries){
			PendingCacheEntry pce = this.pendingCacheEntries.get(response);
			if(pce != null){
				if(!pce.addData(responsedata.getData())){
					logger.debug("Removing pending cache entry because it is too large: ", pce.dataLen, " > ", pce.ceProperties.maxResourceSize);
					this.pendingCacheEntries.remove(response);
				}
			}
		}
	}

	@SubscribeEvent(priority = Priority.LOWEST)
	public void onHTTPResponseEnded(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPResponse response, UpstreamServer upstreamServer) {
		synchronized(this.pendingCacheEntries){
			PendingCacheEntry pce = this.pendingCacheEntries.get(response);
			if(pce != null){
				String key = pce.key;
				logger.debug("Caching resource '", key, "' with maxAge ", pce.ceProperties.maxAge, " (", pce.dataLen, " bytes)");
				this.pendingCacheEntries.remove(response);
				CacheEntry entry = pce.get();
				Proxy.getInstance().dispatchEvent(EVENT_CACHE_STORE, entry);
				this.cache.store(key, entry);
			}
		}
	}

	@SubscribeEvent
	public void onHTTPForwardFailed(SocketConnection downstreamConnection, SocketConnection upstreamConnection, ProxyHTTPRequest request, UpstreamServer userver, int status, String message){
		if(!this.enableServeStale)
			return;
		this.serveFromCache(request, true);
	}


	private void serveFromCache(ProxyHTTPRequest request, boolean error){
		String key = CachePlugin.getCacheKey(request);
		CacheEntry entry = this.cache.fetch(key);
		if(entry != null && (error || !entry.isStale()) && entry.isUsableFor(request, error)){
			HTTPResponse res = new HTTPResponse(entry.getResponse());
			res.setHttpVersion(request.getHttpVersion());
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

			byte[] data;
			if(!etagCondition){
				res.setStatus(HTTPStatus.STATUS_NOT_MODIFIED);
				res.deleteHeader("content-length");
				data = new byte[0];
			}else{
				data = entry.getResponseData();
			}

			this.addHeaders(res, entry, true);
			if(error){
				if(entry.isStale())
					res.addHeader("warning", "111 - \"upstream server unreachable, response is stale\"");
				else
					res.addHeader("warning", "111 - \"upstream server unreachable\"");
			}
			logger.debug("Serving cached response for request '", key, "' (proxy error: ", error, ", stale: ", entry.isStale(), ")");
			HTTPResponseData resdata = new HTTPResponseData(res, data);
			Proxy.getInstance().dispatchEvent(EVENT_CACHE_HIT, request, resdata);
			request.respond(resdata);
		}else{
			Proxy.getInstance().dispatchEvent(EVENT_CACHE_MISS, request);
		}
	}

	private void purgeReply(ProxyHTTPRequest request, int status, String statusmsg, String additional) {
		String resJson = "{\"status\":\"" + statusmsg + "\"";
		if(this.name != null)
			resJson += ",\"server\":\"" + this.servedByPrefix + this.name + "\"";
		if(additional != null)
			resJson += additional;
		resJson += "}";
		request.respond(status, resJson.getBytes(), "content-type", "application/json");
	}

	private boolean tryStartCachingResponse(SocketConnection upstreamConnection, HTTPResponse response, UpstreamServer upstreamServer, String key) {
		CacheConfig cc = this.getConfig(upstreamServer);
		CacheEntry.Properties properties = cc.getResourceProperties(response);
		if(properties != null){
			Proxy.getInstance().dispatchEvent(EVENT_CACHE_CANDIDATE, response, properties);
			synchronized(this.pendingCacheEntries){
				for(PendingCacheEntry p : this.pendingCacheEntries.values()){
					if(p.key.equals(key)) // there is already a pending entry for this key
						return true;
				}
				PendingCacheEntry pce = new PendingCacheEntry(upstreamConnection, response, properties);
				this.pendingCacheEntries.put(response, pce);
			}
			return true;
		}else
			return false;
	}

	private void addHeaders(HTTPMessage msg, CacheEntry entry, boolean hit) {
		if(hit)
			msg.setHeader("age", String.valueOf(entry.age()));
		else if(!msg.headerExists("age"))
			msg.setHeader("age", "0");
		msg.appendHeader("x-cache", hit ? "HIT" : "MISS", ", ");
		msg.appendHeader("x-cache-lookup", entry != null ? "HIT" : "MISS", ", ");
		msg.appendHeader("x-cache-hits", entry != null ? String.valueOf(entry.getHits()) : "0", ", ");
		if(this.name != null)
			msg.appendHeader("x-served-by", this.servedByPrefix + this.name, ", ");
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
			Iterator<java.util.Map.Entry<HTTPResponse, PendingCacheEntry>> iterator = this.pendingCacheEntries.entrySet().iterator();
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
		ResourceCache newCache;
		if(this.caches != null){
			List<ResourceCache> cacheInstances = new java.util.ArrayList<>();
			for(Object o : this.caches){
				if(!(o instanceof ConfigObject))
					throw new IllegalArgumentException("Entries in 'caches' must be objects");
				cacheInstances.add(initCache((ConfigObject) o));
			}
			newCache = new MultiLevelCache(cacheInstances);
		}else{
			newCache = initCache(this.singleCacheConfig);
		}
		if(this.cache != null)
			this.cache.close();
		this.cache = newCache;
		logger.debug("Initialized cache: ", this.cache.getClass().getName(), " (", this.cache, ")");
	}


	private static ResourceCache initCache(ConfigObject obj){
		String type = obj.getString("type");
		Function<? super ConfigObject, ? extends ResourceCache> supplier = CachePlugin.cacheTypes.get(type);
		if(supplier == null)
			throw new IllegalArgumentException("Invalid cache type '" + type + "'");
		return supplier.apply(obj);
	}


	public static String getCacheKey(ProxyHTTPRequest request) {
		return CachePlugin.getCacheKey(request.getInitialMethod(), request.getInitialScheme(), request.getInitialAuthority(), request.getInitialPath());
	}

	public static String getCacheKey(String method, String scheme, String authority, String path) {
		return method + " " + scheme + "://" + authority + path;
	}

	/**
	 * Registers a new implementation of {@link ResourceCache}.
	 * 
	 * @param name The name of this implementation used in the configuration file
	 * @param supplier Generator for new instances of the cache
	 * @return <code>true</code> if the implementation was registered successfully, <code>false</code> if an implementation with the given name already exists
	 */
	public static synchronized boolean registerCacheImplementation(String name, Function<? super ConfigObject, ? extends ResourceCache> supplier) {
		if(CachePlugin.cacheTypes.containsKey(name))
			return false;
		CachePlugin.cacheTypes.put(name, supplier);
		return true;
	}

	/**
	 * Registers a new implementation of {@link ResourceCache} from the given class name.
	 *
	 * @param name The name of this implementation used in the configuration file
	 * @param className The class name of the implementation
	 * @return <code>true</code> if the implementation was registered successfully, <code>false</code> if the class was not found or an implementation with the given name already exists
	 */
	public static boolean registerCacheImplementationByClassName(String name, String className){
		try{
			@SuppressWarnings("unchecked")
			Class<? extends ResourceCache> cl = (Class<? extends ResourceCache>) Class.forName(className);
			java.lang.reflect.Constructor<? extends ResourceCache> clcons = cl.getConstructor(ConfigObject.class);
			return registerCacheImplementation(name, (config) -> {
				try{
					return clcons.newInstance(config);
				}catch(java.lang.reflect.InvocationTargetException e){
					throw new RuntimeException("Constructor of '" + className + "' threw an error", e.getCause());
				}catch(ReflectiveOperationException e){
					throw new RuntimeException("Failed to create an instance of '" + className + "'", e);
				}
			});
		}catch(ReflectiveOperationException e){
			return false;
		}
	}

	/**
	 * Registers a new {@link VaryComparator} which is used to check if the values of two headers are semantically equivalent, and a response containing a <b>Vary</b> HTTP
	 * header may be served for a request when all declared headers are equal or the <code>VaryComparator</code>s return <code>true</code>.
	 * 
	 * @param header The name of the HTTP header whose values this comparator compares
	 * @param comparator The comparator
	 * @return <code>true</code> if a comparator was previously registered for the given header
	 */
	public static boolean registerVaryComparator(String header, VaryComparator comparator) {
		return CachePlugin.varyComparators.put(header, comparator) != null;
	}

	static VaryComparator getVaryComparator(String header) {
		VaryComparator c = CachePlugin.varyComparators.get(header);
		if(c != null)
			return c;
		else
			return VaryComparator.EQUALS_COMPARATOR;
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
		private final HTTPResponse response;
		private final CacheEntry.Properties ceProperties;

		private final ProxyHTTPRequest request;
		private final String key;
		private final long created = time();

		private final int correctedAgeValue;

		private List<byte[]> data = new LinkedList<>();
		private int dataLen = 0;

		public PendingCacheEntry(SocketConnection upstreamConnection, HTTPResponse response, CacheEntry.Properties properties) {
			this.upstreamConnection = upstreamConnection;
			this.response = new HTTPResponse(response);
			this.ceProperties = properties;

			ProxyHTTPRequest request = (ProxyHTTPRequest) response.getOther();
			if(request == null)
				throw new NullPointerException("request is null");
			ProxyHTTPRequest prequest = new ProxyHTTPRequest(request); // may still be used so clone before editing
			prequest.setAuthority(prequest.getInitialAuthority()); // reset any changes
			prequest.setPath(prequest.getInitialPath());
			this.request = prequest;
			this.key = CachePlugin.getCacheKey(this.request);

			this.correctedAgeValue = CachePlugin.parseIntSafe(response.getHeader("age"), 0)
					+ (int) ((response.getCreatedTime() - response.getOther().getCreatedTime()) / 1000);

			this.response.setOther(this.request);
			this.request.setOther(this.response);

			if(!this.response.headerExists("date"))
				this.response.setHeader("date", HTTPCommon.dateString());
		}


		public int getPendingTime() {
			return (int) ((time() - this.created) / 1000);
		}


		public synchronized boolean addData(byte[] d) {
			this.data.add(d);
			this.dataLen += d.length;
			return this.dataLen <= this.ceProperties.maxResourceSize;
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

			this.request.lock();
			this.response.lock();
			return new CacheEntry(this.response, data, time() + (this.ceProperties.maxAge - this.correctedAgeValue) * 1000L, this.correctedAgeValue, this.ceProperties);
		}
	}


	static{
		CachePlugin.registerCacheImplementation("lru", (config) -> {
			org.omegazero.proxyaccelerator.cache.impl.LRUCache cache = new org.omegazero.proxyaccelerator.cache.impl.LRUCache();
			cache.setMaxCacheSize(config.optLong("sizeLimit", (long) (Runtime.getRuntime().maxMemory() * 0.5f)));
			return cache;
		});
		CachePlugin.registerCacheImplementation("softreference", (config) -> {
			return new org.omegazero.proxyaccelerator.cache.impl.SoftReferenceCache();
		});
		CachePlugin.registerCacheImplementationByClassName("disk", "org.omegazero.proxyaccelerator.cache.impl.DiskCache");
	}
}
