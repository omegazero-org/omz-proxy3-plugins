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

import org.omegazero.common.util.PropertyUtil;

public interface ResourceCache {

	public static final int INITIAL_CACHE_CAPACITY = PropertyUtil.getInt("org.omegazero.proxyaccelerator.cache.initialCapacity", 100);


	public void store(String primaryKey, CacheEntry entry);

	public CacheEntry fetch(String primaryKey);

	public CacheEntry delete(String primaryKey);


	public void cleanup();


	public void setMaxCacheSize(long bytes);


	public void close();
}
