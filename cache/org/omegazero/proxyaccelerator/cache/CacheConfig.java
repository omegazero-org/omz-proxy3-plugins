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
import java.util.List;
import java.util.regex.Pattern;

import org.omegazero.common.util.PropertyUtil;
import org.omegazero.proxy.config.ConfigArray;
import org.omegazero.proxy.config.ConfigObject;
import org.omegazero.proxy.http.HTTPMessage;

public class CacheConfig {

	private static final int[] CACHEABLE_STATUSES_DEFAULT = new int[] { 200, 204, 301, 308, 410 };
	private static final int[] CACHEABLE_STATUSES;


	private CacheConfigOverride defOverride;
	private final List<CacheConfigOverride> overrides = new ArrayList<>();

	private CacheConfig() {
	}


	public CacheConfigOverride getOverride(HTTPMessage request) {
		String host = request.getAuthority();
		String path = request.getOrigPath();
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
	 * Determines the maximum time in seconds the given <b>response</b> may be cached based on HTTP headers and this configuration.
	 * 
	 * @param response
	 * @param length   The numeric value of the Content-Length header
	 * @return The time in seconds the given <b>response</b> may be cached. 0 if the response is not cacheable
	 */
	public int maxAge(HTTPMessage response, long length) {
		HTTPMessage request = response.getCorrespondingMessage();
		if(request == null)
			throw new NullPointerException("request is null");

		String cacheControlReq = request.getHeader("cache-control");
		if(cacheControlReq != null && cacheControlReq.toLowerCase().contains("no-store"))
			return 0;

		String method = request.getMethod();
		if(!(method.equals("GET") || method.equals("HEAD")))
			return 0;

		boolean cacheable = false;
		for(int s : CacheConfig.CACHEABLE_STATUSES){
			if(s == response.getStatus()){
				cacheable = true;
				break;
			}
		}
		if(!cacheable)
			return 0;

		CacheConfigOverride override = this.getOverride(request);
		if(override == null)
			return 0; // no path matched (only happens when disabled)

		if(length > override.maxResourceSize)
			return 0;

		if("*".equals(response.getHeader("vary")))
			return 0;

		String cacheControl = response.getHeader("cache-control");

		int maxAge = 0;
		if(cacheControl == null)
			maxAge = override.defaultMaxAge;
		else{
			CacheControlParameters params = CacheConfig.parseCacheControl(cacheControl);

			// revalidation is not supported
			if((params.flags & (CacheControlParameters.MUST_REVALIDATE | CacheControlParameters.MUST_REVALIDATE_PROXY | CacheControlParameters.NOCACHE
					| CacheControlParameters.NOSTORE | CacheControlParameters.PRIVATE)) != 0)
				maxAge = 0;
			else if(params.maxAgeShared > 0)
				maxAge = params.maxAgeShared;
			else if(params.maxAge > 0)
				maxAge = params.maxAge;
			else
				maxAge = override.defaultMaxAge;

			if(override.maxAgeOverride >= 0 && (!override.maxAgeOverrideCacheableOnly || maxAge > 0))
				maxAge = override.maxAgeOverride;
		}
		return maxAge;
	}

	/**
	 * Checks if the response in the given cache entry may be used as a response to the given <b>request</b> based on this configuration. This method does not check if the
	 * cache entry represents the resource requested (use {@link CacheEntry#isUsableFor(HTTPMessage)}).
	 * 
	 * @param request The request
	 * @param cache
	 * @return <code>true</code> if this configuration allows the response data of the cache entry to be used to generate a response for the given request
	 */
	public boolean isUsable(HTTPMessage request, CacheEntry cache) {
		CacheConfigOverride override = this.getOverride(request);
		if(override == null)
			return false;

		if(override.ignoreClientRefresh)
			return true;

		String cacheControl = request.getHeader("cache-control");
		if(cacheControl == null)
			return !cache.isStale();

		CacheControlParameters params = CacheConfig.parseCacheControl(cacheControl);
		if((params.flags & CacheControlParameters.NOCACHE) != 0) // revalidation is not supported
			return false;
		if(params.maxAge >= 0 && cache.age() > params.maxAge)
			return false;
		if(params.minFresh >= 0 && cache.freshRemaining() < params.minFresh)
			return false;
		if(params.maxStale >= 0 && -cache.freshRemaining() < params.maxStale)
			return true;
		return !cache.isStale();
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
					throw new IllegalArgumentException("Values in 'pathOverrides' array must be objects");
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
					obj.optBoolean("ignoreClientRefresh", parent.ignoreClientRefresh), obj.optInt("maxResourceSize", parent.maxResourceSize),
					obj.optString("purgeKey", parent.purgeKey), obj.optBoolean("propagatePurgeRequest", parent.propagatePurgeRequest));
		}else{
			return new CacheConfigOverride(Pattern.compile(host), Pattern.compile(path), obj.optInt("defaultMaxAge", 0), obj.optInt("maxAgeOverride", -1),
					obj.optBoolean("maxAgeOverrideCacheableOnly", false), obj.optBoolean("ignoreClientRefresh", false), obj.optInt("maxResourceSize", 0x100000 /* 1MiB */),
					obj.optString("purgeKey", null)/* default null = disable PURGE */, obj.optBoolean("propagatePurgeRequest", false));
		}
	}

	private static CacheControlParameters parseCacheControl(String value) {
		CacheControlParameters params = new CacheControlParameters();
		String[] parts = value.toLowerCase().split(",");
		for(String part : parts){
			part = part.trim();
			int flag = getCacheControlFieldFlag(part);
			if(flag > 0)
				params.flags |= flag;
			else if(part.startsWith("max-age"))
				params.maxAge = getNumberArgumentValue(part, -1);
			else if(part.startsWith("s-maxage"))
				params.maxAgeShared = getNumberArgumentValue(part, -1);
			else if(part.startsWith("max-stale"))
				params.maxStale = getNumberArgumentValue(part, Integer.MAX_VALUE);
			else if(part.startsWith("min-fresh"))
				params.minFresh = getNumberArgumentValue(part, -1);
		}
		return params;
	}

	private static int getCacheControlFieldFlag(String field) {
		switch(field){
			case "must-revalidate":
				return CacheControlParameters.MUST_REVALIDATE;
			case "proxy-revalidate":
				return CacheControlParameters.MUST_REVALIDATE_PROXY;
			case "no-cache":
				return CacheControlParameters.NOCACHE;
			case "no-store":
				return CacheControlParameters.NOSTORE;
			case "no-transform":
				return CacheControlParameters.NOTRANSFORM;
			case "public":
				return CacheControlParameters.PUBLIC;
			case "private":
				return CacheControlParameters.PRIVATE;
			case "immutable":
				return CacheControlParameters.IMMUTABLE;
			default:
				return 0;
		}
	}

	private static int getNumberArgumentValue(String value, int def) {
		int i = value.indexOf('=');
		if(i < 0)
			return def;
		String numstr = value.substring(i + 1);
		if(numstr.length() < 1)
			return def;
		if(numstr.charAt(0) == '"' && numstr.charAt(numstr.length() - 1) == '"')
			numstr = numstr.substring(1, numstr.length() - 1);
		return CachePlugin.parseIntSafe(numstr, def);
	}


	public static class CacheConfigOverride {

		private final Pattern hostMatcher;
		private final Pattern pathMatcher;

		private final int defaultMaxAge;
		private final int maxAgeOverride;
		private final boolean maxAgeOverrideCacheableOnly;
		private final boolean ignoreClientRefresh;
		private final int maxResourceSize;

		private final String purgeKey;
		private final boolean propagatePurgeRequest;

		public CacheConfigOverride(Pattern hostMatcher, Pattern pathMatcher, int defaultMaxAge, int maxAgeOverride, boolean maxAgeOverrideCacheableOnly,
				boolean ignoreClientRefresh, int maxResourceSize, String purgeKey, boolean propagatePurgeRequest) {
			this.hostMatcher = hostMatcher;
			this.pathMatcher = pathMatcher;
			this.defaultMaxAge = defaultMaxAge;
			this.maxAgeOverride = maxAgeOverride;
			this.maxAgeOverrideCacheableOnly = maxAgeOverrideCacheableOnly;
			this.ignoreClientRefresh = ignoreClientRefresh;
			this.maxResourceSize = maxResourceSize;
			this.purgeKey = purgeKey;
			this.propagatePurgeRequest = propagatePurgeRequest;
		}


		public String getPurgeKey() {
			return purgeKey;
		}

		public boolean isPropagatePurgeRequest() {
			return propagatePurgeRequest;
		}
	}

	private static class CacheControlParameters {

		public static final int MUST_REVALIDATE = 1;
		public static final int MUST_REVALIDATE_PROXY = 2;
		public static final int NOCACHE = 4;
		public static final int NOSTORE = 8;
		public static final int NOTRANSFORM = 16;
		public static final int PUBLIC = 32;
		public static final int PRIVATE = 64;
		public static final int IMMUTABLE = 128;

		private int flags = 0;
		private int maxAge = -1;
		private int maxAgeShared = -1;
		private int maxStale = -1;
		private int minFresh = -1;
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
