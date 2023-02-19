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

import java.util.Map;

import org.omegazero.http.common.HTTPMessage;
import org.omegazero.http.common.HTTPRequest;
import org.omegazero.http.common.HTTPResponse;

public class CacheEntry implements java.io.Serializable {

	private static final long serialVersionUID = 1L;

	private final HTTPResponse response;
	private final byte[] responseData;
	private final long expiresAt;
	private final int correctedAgeValue;
	private final Properties properties;

	private final long creationTime;

	private int hits;

	public CacheEntry(HTTPResponse response, byte[] responseData, long expiresAt, int correctedAgeValue, Properties properties) {
		this.response = response;
		this.responseData = responseData;
		this.expiresAt = expiresAt;
		this.correctedAgeValue = correctedAgeValue;
		this.properties = properties;

		this.creationTime = CachePlugin.time();
	}


	/**
	 * Checks if the request header values of the headers declared in the <code>Vary</code> header in the cached response match the values of the given <b>request</b>.
	 * 
	 * @param request The request to check the Vary header values of the request of this cached response against
	 * @return <code>true</code> if the Vary request headers match in both requests or the Vary header in the response was empty or nonexistent
	 */
	public boolean isVaryMatching(HTTPRequest request) {
		return this.properties.isVaryMatching(request);
	}

	/**
	 * Checks if this <code>CacheEntry</code> is suitable to be used as a response to the given <b>request</b>.
	 * <p>
	 * An entry is is considered suitable if all <i>Vary</i> headers match and the response is allowed to be served according to <i>Cache-Control</i> settings in the request and response.
	 * <b>This method does not check if the HTTP request parameters (method, path, etc) are equal.</b>
	 *
	 * @param request The request
	 * @param error If {@code true}, allows this {@code CacheEntry} to be used even if it is stale but within the limit set by the <i>stale-if-error</i> response directive
	 * @return <code>true</code> if this entry is suitable to be used as a response to the given <b>request</b>
	 * @see #isVaryMatching(HTTPMessage)
	 * @see CacheConfig#isUsable(HTTPMessage, CacheEntry)
	 */
	public boolean isUsableFor(HTTPRequest request, boolean error) {
		if(!this.isVaryMatching(request))
			return false;

		if(this.getProperties().ignoreClientRefresh)
			return true;

		long freshRemaining = this.freshRemaining();

		if(error && -freshRemaining < this.getProperties().maxStaleIfError)
			return true;

		String cacheControl = request.getHeader("cache-control");
		if(cacheControl != null){
			CacheControlUtil.CacheControlParameters params = CacheControlUtil.parseCacheControl(cacheControl);
			if((params.getFlags() & CacheControlUtil.CacheControlParameters.NOCACHE) != 0) // revalidation is not supported
				return false;
			if(params.getMaxAge() >= 0 && this.age() > params.getMaxAge())
				return false;
			if(params.getMinFresh() >= 0 && freshRemaining < params.getMinFresh())
				return false;
			if(params.getMaxStale() >= 0 && -freshRemaining < params.getMaxStale())
				return true;
		}

		return !this.isStale();
	}


	/**
	 * Returns the number of seconds this entry is still considered fresh. May be negative if this entry has expired.
	 * 
	 * @return The remaining number of fresh seconds
	 */
	public long freshRemaining() {
		return (this.expiresAt - CachePlugin.time()) / 1000;
	}

	/**
	 * Returns <code>true</code> if the time at which this entry becomes stale is in the past.
	 * 
	 * @return {@code true} if this entry is stale
	 */
	public boolean isStale() {
		return this.expiresAt < CachePlugin.time();
	}

	/**
	 * Returns a rough estimation of the amount of memory this cache entry uses in bytes.
	 * 
	 * @return The used memory in bytes
	 */
	public long getSize() {
		return 8192 + this.responseData.length + this.properties.getVaryValuesSize() * 128 + 48;
	}

	/**
	 * Returns the age of this entry (calculated as: <code>corrected_age_value + resident_time</code>).
	 * 
	 * @return The age in seconds
	 */
	public int age() {
		return (int) ((CachePlugin.time() - this.creationTime) / 1000 + this.correctedAgeValue);
	}

	/**
	 * Increments the hit count by one and returns the new value.
	 * 
	 * @return The new hit count
	 */
	public int incrementHits() {
		return ++this.hits;
	}


	public HTTPResponse getResponse() {
		return this.response;
	}

	public byte[] getResponseData() {
		return this.responseData;
	}

	public long getExpiresAt() {
		return this.expiresAt;
	}

	public int getCorrectedAgeValue() {
		return this.correctedAgeValue;
	}

	public Properties getProperties() {
		return this.properties;
	}

	public long getCreationTime() {
		return this.creationTime;
	}

	public int getHits() {
		return this.hits;
	}


	public static class Properties implements java.io.Serializable {

		private static final long serialVersionUID = 1L;

		public final int maxResourceSize;
		public final boolean ignoreClientRefresh;

		public final int maxAge;
		public final int maxStaleIfError;
		private final Map<String, String> varyValues;

		public Properties(CacheConfig.CacheConfigOverride config, int maxAge, int maxStaleIfError, boolean immutable, Map<String, String> varyValues) {
			this.maxResourceSize = config.maxResourceSize;
			this.ignoreClientRefresh = config.ignoreClientRefresh || immutable && config.ignoreClientRefreshIfImmutable;
			this.maxAge = maxAge;
			this.maxStaleIfError = maxStaleIfError;
			this.varyValues = varyValues;
		}


		public boolean isVaryMatching(HTTPMessage request) {
			for(String k : this.varyValues.keySet()){
				if(!CachePlugin.getVaryComparator(k).semanticallyEquivalent(request.getHeader(k), this.varyValues.get(k)))
					return false;
			}
			return true;
		}


		public int getVaryValuesSize() {
			return this.varyValues.size();
		}
	}
}
