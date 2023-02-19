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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.omegazero.common.config.ConfigArray;
import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.util.PropertyUtil;
import org.omegazero.http.common.HTTPRequest;
import org.omegazero.http.common.HTTPResponse;
import org.omegazero.proxyaccelerator.cache.CacheControlUtil.CacheControlParameters;

public class CacheConfig {

	private static final int[] CACHEABLE_STATUSES_DEFAULT = new int[] { 200, 203, 204, 300, 301, 308, 404, 405, 410, 414, 501 };
	private static final int[] CACHEABLE_STATUSES;


	private CacheConfigOverride defOverride;
	private final List<CacheConfigOverride> overrides = new ArrayList<>();

	private CacheConfig() {
	}


	public CacheConfigOverride getOverride(HTTPRequest request) {
		String host = request.getAuthority();
		String path = request.getPath();
		if(host == null)
			host = "";
		for(CacheConfigOverride override : this.overrides){
			if(override.hostMatcher.matcher(host).matches() && override.pathMatcher.matcher(path).matches()){
				return override;
			}
		}
		return null;
	}

	/**
	 * Determines several cache properties of the given <b>response</b> based on HTTP headers and this configuration.
	 * 
	 * @param response
	 * @return Cache properties of the response, or <code>null</code> if the response is not cacheable
	 */
	public CacheEntry.Properties getResourceProperties(HTTPResponse response) {
		int rstatus = response.getStatus();
		// <200 must not be cached; 206 (Range response) is not supported; 304 standalone should not be cached, but it still contains the cache-control header
		// for other response codes >=400, the upstream server should decide in a cache-control header whether it makes sense to cache the response
		if(rstatus < 200 || rstatus == 206 || rstatus == 304)
			return null;

		HTTPRequest request = response.getOther();
		if(request == null)
			throw new NullPointerException("request is null");

		if(request.headerExists("authorization"))
			return null;

		String cacheControlReq = request.getHeader("cache-control");
		if(cacheControlReq != null && cacheControlReq.toLowerCase().contains("no-store"))
			return null;

		String method = request.getMethod();
		if(!(method.equals("GET") || method.equals("HEAD")))
			return null;

		CacheConfigOverride override = this.getOverride(request);
		if(override == null)
			return null; // no path matched (only happens when disabled)

		String vary = response.getHeader("vary");
		if("*".equals(vary))
			return null;

		boolean statusCacheable = false;
		for(int s : CacheConfig.CACHEABLE_STATUSES){
			if(s == rstatus){
				statusCacheable = true;
				break;
			}
		}

		String cacheControl = response.getHeader("cache-control");

		int maxAge = 0;
		int maxStaleIfError = 0;
		boolean immutable = false;
		if(cacheControl != null){
			CacheControlParameters params = CacheControlUtil.parseCacheControl(cacheControl);

			// revalidation is not supported
			if((params.getFlags() & (CacheControlParameters.MUST_REVALIDATE | CacheControlParameters.MUST_REVALIDATE_PROXY | CacheControlParameters.NOCACHE
					| CacheControlParameters.NOSTORE | CacheControlParameters.PRIVATE)) != 0)
				maxAge = 0;
			else if(params.getMaxAgeShared() >= 0)
				maxAge = params.getMaxAgeShared();
			else if(params.getMaxAge() >= 0)
				maxAge = params.getMaxAge();
			else if(statusCacheable || (params.getFlags() & CacheControlParameters.PUBLIC) != 0)
				maxAge = override.defaultMaxAge;

			if(override.maxAgeOverride >= 0 && (!override.maxAgeOverrideCacheableOnly || maxAge > 0))
				maxAge = override.maxAgeOverride;

			immutable = (params.getFlags() & (CacheControlParameters.IMMUTABLE | CacheControlParameters.IMMUTABLE_SHARED)) != 0;

			maxStaleIfError = params.getMaxStaleIfError();
		}else if(statusCacheable)
			maxAge = override.defaultMaxAge;
		if(maxAge <= 0)
			return null;

		Map<String, String> varyValues = new HashMap<>();
		if(vary != null){ // the value "*" is checked for above
			String[] varyHeaders = vary.split(",");
			for(String v : varyHeaders){
				v = v.trim().toLowerCase();
				varyValues.put(v, request.getHeader(v));
			}
		}

		return new CacheEntry.Properties(override, maxAge, maxStaleIfError, immutable, varyValues);
	}

	/**
	 * Checks if the response in the given cache entry may be used as a response to the given <b>request</b> based on this configuration. This method does not check if the
	 * cache entry represents the resource requested.
	 * 
	 * @param request The request
	 * @param cache The cache entry
	 * @return <code>true</code> if this configuration allows the response data of the cache entry to be used to generate a response for the given request
	 * @deprecated Use {@link CacheEntry#isUsableFor(HTTPRequest, boolean)}
	 */
	@Deprecated
	public boolean isUsable(HTTPRequest request, CacheEntry cache) {
		return cache.isUsableFor(request, false);
	}


	public static CacheConfig from(ConfigObject obj, CacheConfig configParent) {
		CacheConfig cfg = new CacheConfig();

		if(!obj.optBoolean("enable", true) || (configParent != null && configParent.overrides.size() < 1)){
			return cfg;
		}

		CacheConfigOverride defOverride = CacheConfig.genOverride(".*", ".*", obj, configParent != null ? configParent.defOverride : null);

		ConfigArray array = obj.optArray("overrides");
		if(array != null){
			for(Object ao : array){
				if(!(ao instanceof ConfigObject))
					throw new IllegalArgumentException("Values in 'overrides' array must be objects");
				ConfigObject oobj = (ConfigObject) ao;
				CacheConfigOverride parent = oobj.optBoolean("inherit", true) ? defOverride : null;
				String host = oobj.optString("hostname", ".*");
				Object po = oobj.get("path");
				if(po instanceof ConfigArray){
					for(Object p : (ConfigArray) po){
						if(!(p instanceof String))
							throw new IllegalArgumentException("Values in 'path' array must be strings");
						cfg.overrides.add(CacheConfig.genOverride(host, (String) p, oobj, parent));
					}
				}else if(po instanceof String){
					cfg.overrides.add(CacheConfig.genOverride(host, (String) po, oobj, parent));
				}else
					throw new IllegalArgumentException("'path' must either be a string or an array");
			}
		}

		cfg.defOverride = defOverride;
		cfg.overrides.add(defOverride);
		return cfg;
	}

	private static CacheConfigOverride genOverride(String host, String path, ConfigObject obj, CacheConfigOverride parent) {
		if(parent != null){
			return new CacheConfigOverride(Pattern.compile(host), Pattern.compile(path), obj.optInt("defaultMaxAge", parent.defaultMaxAge),
					obj.optInt("maxAgeOverride", parent.maxAgeOverride), obj.optBoolean("maxAgeOverrideCacheableOnly", parent.maxAgeOverrideCacheableOnly),
					obj.optBoolean("ignoreClientRefresh", parent.ignoreClientRefresh), obj.optBoolean("ignoreClientRefreshIfImmutable", parent.ignoreClientRefreshIfImmutable),
					obj.optInt("maxResourceSize", parent.maxResourceSize), obj.optString("purgeKey", parent.purgeKey),
					obj.optBoolean("propagatePurgeRequest", parent.propagatePurgeRequest), obj.optBoolean("wildcardPurgeEnabled", parent.wildcardPurgeEnabled));
		}else{
			return new CacheConfigOverride(Pattern.compile(host), Pattern.compile(path), obj.optInt("defaultMaxAge", 0), obj.optInt("maxAgeOverride", -1),
					obj.optBoolean("maxAgeOverrideCacheableOnly", false), obj.optBoolean("ignoreClientRefresh", false),
					obj.optBoolean("ignoreClientRefreshIfImmutable", false), obj.optInt("maxResourceSize", 0x100000 /* 1MiB */),
					obj.optString("purgeKey", null) /* default null = disable PURGE */, obj.optBoolean("propagatePurgeRequest", false),
					obj.optBoolean("wildcardPurgeEnabled", false));
		}
	}


	public static class CacheConfigOverride {

		public final Pattern hostMatcher;
		public final Pattern pathMatcher;

		public final int defaultMaxAge;
		public final int maxAgeOverride;
		public final boolean maxAgeOverrideCacheableOnly;
		public final boolean ignoreClientRefresh;
		public final boolean ignoreClientRefreshIfImmutable;
		public final int maxResourceSize;

		public final String purgeKey;
		public final boolean propagatePurgeRequest;
		public final boolean wildcardPurgeEnabled;

		CacheConfigOverride(Pattern hostMatcher, Pattern pathMatcher, int defaultMaxAge, int maxAgeOverride, boolean maxAgeOverrideCacheableOnly, boolean ignoreClientRefresh,
				boolean ignoreClientRefreshIfImmutable, int maxResourceSize, String purgeKey, boolean propagatePurgeRequest, boolean wildcardPurgeEnabled) {
			this.hostMatcher = hostMatcher;
			this.pathMatcher = pathMatcher;
			this.defaultMaxAge = defaultMaxAge;
			this.maxAgeOverride = maxAgeOverride;
			this.maxAgeOverrideCacheableOnly = maxAgeOverrideCacheableOnly;
			this.ignoreClientRefresh = ignoreClientRefresh;
			this.ignoreClientRefreshIfImmutable = ignoreClientRefreshIfImmutable;
			this.maxResourceSize = maxResourceSize;
			this.purgeKey = purgeKey;
			this.propagatePurgeRequest = propagatePurgeRequest;
			this.wildcardPurgeEnabled = wildcardPurgeEnabled;
		}
	}


	static{
		String extraStatuses = PropertyUtil.getString("org.omegazero.proxyaccelerator.cache.cacheableStatuses", null);
		String[] parts = extraStatuses != null ? extraStatuses.split(",") : new String[0];
		int[] statuses = new int[CACHEABLE_STATUSES_DEFAULT.length + parts.length];
		System.arraycopy(CACHEABLE_STATUSES_DEFAULT, 0, statuses, 0, CACHEABLE_STATUSES_DEFAULT.length);
		for(int i = 0; i < parts.length; i++)
			statuses[i] = Integer.parseInt(parts[i].trim());
		CACHEABLE_STATUSES = statuses;
	}
}
