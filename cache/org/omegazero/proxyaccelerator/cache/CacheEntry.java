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

import org.omegazero.proxy.http.HTTPMessage;

public class CacheEntry {

	private final HTTPMessage request;
	private final HTTPMessage response;
	private final byte[] responseData;
	private final long expiresAt;
	private final int correctedAgeValue;
	private final Properties properties;

	private final long creationTime;

	private int hits;

	public CacheEntry(HTTPMessage request, HTTPMessage response, byte[] responseData, long expiresAt, int correctedAgeValue, Properties properties) {
		this.request = request;
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
	public boolean isVaryMatching(HTTPMessage request) {
		return this.properties.isVaryMatching(request);
	}

	/**
	 * Checks if this <code>CacheEntry</code> is suitable to be used as a response to the given <b>request</b>. It is considered suitable if this entry is not stale, the
	 * request lines of the given request and the request of this entry are equal and all Vary headers match.
	 * 
	 * @param request
	 * @return <code>true</code> if this entry is suitable to be used as a response to the given <b>request</b>
	 * @see HTTPMessage#equalStartLine(HTTPMessage)
	 * @see #isVaryMatching(HTTPMessage)
	 */
	public boolean isUsableFor(HTTPMessage request) {
		return !this.isStale() && this.request.equalStartLine(request) && this.isVaryMatching(request);
	}


	/**
	 * 
	 * @return The number of seconds this entry is still considered fresh. May be negative if this entry has expired
	 */
	public long freshRemaining() {
		return (this.expiresAt - CachePlugin.time()) / 1000;
	}

	/**
	 * 
	 * @return <code>true</code> if the time at which this entry expires is in the past
	 */
	public boolean isStale() {
		return this.expiresAt < CachePlugin.time();
	}

	/**
	 * 
	 * @return A rough estimation of the amount of memory this cache entry uses in bytes
	 */
	public long getSize() {
		return this.request.getSize() + this.response.getSize() + this.responseData.length + this.properties.getVaryValuesSize() * 128 + 48;
	}

	/**
	 * 
	 * @return The age of this entry (calculated as: <code>corrected_age_value + resident_time</code>)
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


	public HTTPMessage getResponse() {
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


	public static class Properties {

		private final Object config;
		private final int maxDataSize;
		private final int maxAge;
		private final Map<String, String> varyValues;
		private final boolean immutable;

		public Properties(Object config, int maxDataSize, int maxAge, Map<String, String> varyValues, boolean immutable) {
			this.config = config;
			this.maxDataSize = maxDataSize;
			this.maxAge = maxAge;
			this.varyValues = varyValues;
			this.immutable = immutable;
		}


		public boolean isVaryMatching(HTTPMessage request) {
			for(String k : this.varyValues.keySet()){
				if(!CachePlugin.getVaryComparator(k).semanticallyEquivalent(request.getHeader(k), this.varyValues.get(k)))
					return false;
			}
			return true;
		}


		public Object getConfig() {
			return this.config;
		}

		public int getMaxDataSize() {
			return this.maxDataSize;
		}

		public int getMaxAge() {
			return this.maxAge;
		}

		public int getVaryValuesSize() {
			return this.varyValues.size();
		}

		public boolean isImmutable() {
			return this.immutable;
		}
	}
}
