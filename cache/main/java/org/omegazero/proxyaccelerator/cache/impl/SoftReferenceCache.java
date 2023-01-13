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

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;

import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.proxyaccelerator.cache.CacheEntry;
import org.omegazero.proxyaccelerator.cache.ResourceCache;

public class SoftReferenceCache implements ResourceCache {

	private static final Logger logger = LoggerUtil.createLogger();


	private final Map<String, SoftCacheEntryReference> cache = new HashMap<>(ResourceCache.INITIAL_CACHE_CAPACITY);
	private final ReferenceQueue<CacheEntry> refQueue = new ReferenceQueue<>();


	private synchronized void removeExpungedEntries() {
		SoftCacheEntryReference ref;
		while((ref = (SoftCacheEntryReference) this.refQueue.poll()) != null){
			if(this.cache.remove(ref.key) == null){
				logger.warn("Could not delete soft reference with key '", ref.key, "' because it is not in the cache");
			}else{
				logger.trace("Cache entry '", ref.key, "' removed because it no longer exists");
			}
		}
	}


	@Override
	public synchronized void store(String primaryKey, CacheEntry entry) {
		this.removeExpungedEntries();
		this.cache.put(primaryKey, new SoftCacheEntryReference(entry, this.refQueue, primaryKey));
	}

	@Override
	public synchronized CacheEntry fetch(String primaryKey) {
		this.removeExpungedEntries();
		Reference<CacheEntry> ref = this.cache.get(primaryKey);
		if(ref == null)
			return null;
		CacheEntry entry = ref.get();
		if(entry == null || entry.isStale()){
			this.cache.remove(primaryKey);
			return null;
		}else
			return entry;
	}

	@Override
	public synchronized CacheEntry delete(String primaryKey) {
		this.removeExpungedEntries();
		Reference<CacheEntry> ref = this.cache.remove(primaryKey);
		if(ref == null)
			return null;
		else
			return ref.get();
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
		Iterator<SoftCacheEntryReference> iterator = this.cache.values().iterator();
		while(iterator.hasNext()){
			SoftCacheEntryReference ref = iterator.next();
			CacheEntry entry = ref.get();
			if(entry == null || entry.isStale())
				iterator.remove();
		}
	}

	@Override
	public void setMaxCacheSize(long bytes) {
	}

	@Override
	public void close() {
	}


	private static class SoftCacheEntryReference extends SoftReference<CacheEntry> {

		private final String key;

		public SoftCacheEntryReference(CacheEntry entry, ReferenceQueue<? super CacheEntry> queue, String key) {
			super(entry, queue);
			this.key = key;
		}
	}
}
