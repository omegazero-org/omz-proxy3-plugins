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

import java.util.function.Predicate;

import org.omegazero.common.util.PropertyUtil;

/**
 * Stores {@linkplain CacheEntry cache entries}, each associated with a single primary key string.
 */
public interface ResourceCache {

	/**
	 * System property <code>org.omegazero.proxyaccelerator.cache.initialCapacity</code><br>
	 * <br>
	 * The initial capacity of {@link ResourceCache}s.<br>
	 * <br>
	 * <b>Default:</b> <code>100</code>
	 */
	public static final int INITIAL_CACHE_CAPACITY = PropertyUtil.getInt("org.omegazero.proxyaccelerator.cache.initialCapacity", 100);


	/**
	 * Stores the given {@link CacheEntry} with the given primary key in this cache.
	 * 
	 * @param primaryKey The primary key
	 * @param entry      The {@link CacheEntry}
	 */
	public void store(String primaryKey, CacheEntry entry);

	/**
	 * Fetches a {@link CacheEntry} associated with the given primary key from this cache. <code>null</code> is returned if there is no entry associated with the given key, or
	 * an existing entry is {@linkplain CacheEntry#isStale() stale} or otherwise invalid.
	 * 
	 * @param primaryKey The primary key
	 * @return The {@link CacheEntry}, or <code>null</code> if there is no valid entry associated with the given key
	 */
	public CacheEntry fetch(String primaryKey);

	/**
	 * Deletes a {@link CacheEntry} associated with the given primary key from this cache and returns the deleted entry. The returned entry may be
	 * {@linkplain CacheEntry#isStale() stale}.
	 * 
	 * @param primaryKey The primary key
	 * @return The deleted {@link CacheEntry}, or <code>null</code> if there was no entry associated with the given key
	 */
	public CacheEntry delete(String primaryKey);

	/**
	 * Deletes all {@linkplain CacheEntry cache entries} that match the given <b>filter</b> and returns the number of deleted entries. <code>-1</code> is returned if this
	 * method is not supported by the cache.
	 * 
	 * @param filter The filter
	 * @return The number of deleted entries, or <code>-1</code> if this cache does not support this method
	 * @since 1.3
	 */
	public default int deleteIfKey(Predicate<String> filter) {
		return -1;
	}


	/**
	 * Performs internal cleanup operations, for example deleting {@linkplain CacheEntry#isStale() stale} entries.
	 */
	public void cleanup();


	/**
	 * Sets the maximum amount of memory in bytes the cache may use for resources. Note that this value is only a recommendation: the cache may also use more or less memory
	 * than the given value or may ignore this value entirely.
	 * 
	 * @param bytes The recommended maximum size in bytes
	 * @deprecated Since 1.4, pass max cache size in constructor config instead
	 */
	@Deprecated
	public default void setMaxCacheSize(long bytes){
	}


	/**
	 * Deletes this cache. Behavior of all methods in this cache is undefined after calling this method.
	 */
	public void close();
}
