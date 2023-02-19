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
package org.omegazero.proxyaccelerator.cache.impl;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.proxyaccelerator.cache.CacheEntry;
import org.omegazero.proxyaccelerator.cache.ResourceCache;

public class LRUCache implements ResourceCache {

	private static final Logger logger = LoggerUtil.createLogger();


	private final LRUCacheHashMap cache = new LRUCacheHashMap();

	private long maxCacheSize;
	private long cacheSize = 0;


	@Override
	public synchronized void store(String primaryKey, CacheEntry entry) {
		this.cache.put(primaryKey, entry);
		long size = entry.getSize();
		this.cacheSize += size;
		float capacity = (float) this.cacheSize / this.maxCacheSize;
		if(capacity > 1.2f){
			logger.debug("Cache is at ", capacity * 100, "% capacity, deleting old entries");
			Iterator<CacheEntry> it = this.cache.values().iterator();
			while(it.hasNext() && this.cacheSize > this.maxCacheSize){
				this.cacheSize -= it.next().getSize();
				it.remove();
			}
		}
	}

	@Override
	public synchronized CacheEntry fetch(String primaryKey) {
		return this.cache.get(primaryKey);
	}

	@Override
	public synchronized CacheEntry delete(String primaryKey) {
		CacheEntry entry = this.cache.remove(primaryKey);
		if(entry != null)
			this.cacheSize -= entry.getSize();
		return entry;
	}

	@Override
	public synchronized int deleteIfKey(Predicate<String> filter) {
		int deleted = 0;
		Iterator<String> iterator = this.cache.keySet().iterator();
		while(iterator.hasNext()){
			if(filter.test(iterator.next())){
				iterator.remove();
				deleted++;
			}
		}
		return deleted;
	}

	@Override
	public synchronized void cleanup() {
		Iterator<CacheEntry> iterator = this.cache.values().iterator();
		while(iterator.hasNext()){
			CacheEntry entry = iterator.next();
			if(entry.isStale()){
				iterator.remove();
				this.cacheSize -= entry.getSize();
			}
		}
	}

	@Override
	@SuppressWarnings("deprecation")
	public void setMaxCacheSize(long bytes) {
		this.maxCacheSize = bytes;
	}

	@Override
	public void close() {
	}


	private class LRUCacheHashMap extends LinkedHashMap<String, CacheEntry> {

		private static final long serialVersionUID = 1L;


		public LRUCacheHashMap() {
			super(ResourceCache.INITIAL_CACHE_CAPACITY, 0.75f, true);
		}


		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
			if(LRUCache.this.cacheSize > LRUCache.this.maxCacheSize){
				LRUCache.this.cacheSize -= eldest.getValue().getSize();
				return true;
			}else
				return false;
		}
	}
}
