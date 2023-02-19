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

/**
 * Class used for parsing <i>Cache-Control</i> HTTP headers.
 */
public final class CacheControlUtil {


	private CacheControlUtil() {
	}


	/**
	 * Parses the given <b>value</b> of a <i>Cache-Control</i> HTTP header.
	 * 
	 * @param value The value string of the header
	 * @return The parsed {@link CacheControlParameters}
	 */
	public static CacheControlParameters parseCacheControl(String value) {
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
				params.maxStale = getNumberArgumentValue(part, -1);
			else if(part.startsWith("min-fresh"))
				params.minFresh = getNumberArgumentValue(part, -1);
			else if(part.startsWith("stale-if-error"))
				params.maxStaleIfError = getNumberArgumentValue(part, -1);
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
			case "s-immutable":
				return CacheControlParameters.IMMUTABLE_SHARED;
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


	/**
	 * Contains the parsed properties of a <i>Cache-Control</i> HTTP header.
	 * 
	 * @see CacheControlUtil#parseCacheControl(String)
	 */
	public static class CacheControlParameters {

		public static final int MUST_REVALIDATE = 1;
		public static final int MUST_REVALIDATE_PROXY = 2;
		public static final int NOCACHE = 4;
		public static final int NOSTORE = 8;
		public static final int NOTRANSFORM = 16;
		public static final int PUBLIC = 32;
		public static final int PRIVATE = 64;
		public static final int IMMUTABLE = 128;
		public static final int IMMUTABLE_SHARED = 256;

		private int flags = 0;
		private int maxAge = -1;
		private int maxAgeShared = -1;
		private int maxStale = -1;
		private int minFresh = -1;
		private int maxStaleIfError = -1;


		public int getFlags() {
			return this.flags;
		}

		public int getMaxAge() {
			return this.maxAge;
		}

		public int getMaxAgeShared() {
			return this.maxAgeShared;
		}

		public int getMaxStale() {
			return this.maxStale;
		}

		public int getMinFresh() {
			return this.minFresh;
		}

		public int getMaxStaleIfError() {
			return this.maxStaleIfError;
		}
	}
}
