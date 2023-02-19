/*
 * Copyright (C) 2023 omegazero.org, warp03
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxyaccelerator.cache;

import java.util.List;
import java.util.function.Predicate;

public class MultiLevelCache implements ResourceCache {

	private final List<ResourceCache> caches;

	public MultiLevelCache(List<ResourceCache> caches){
		this.caches = caches;
	}


	@Override
	public void store(String primaryKey, CacheEntry entry){
		for(ResourceCache cache : this.caches)
			cache.store(primaryKey, entry);
	}

	@Override
	public CacheEntry fetch(String primaryKey){
		for(int i = 0; i < this.caches.size(); i++){
			ResourceCache cache = this.caches.get(i);
			CacheEntry entry = cache.fetch(primaryKey);
			if(entry != null){
				if(!entry.isStale()){
					for(int j = i - 1; j >= 0; j--)
						this.caches.get(j).store(primaryKey, entry);
				}
				return entry;
			}
		}
		return null;
	}

	@Override
	public CacheEntry delete(String primaryKey){
		CacheEntry entry = null;
		for(ResourceCache cache : this.caches){
			CacheEntry e = cache.delete(primaryKey);
			if(entry == null)
				entry = e;
		}
		return entry;
	}

	@Override
	public int deleteIfKey(Predicate<String> filter){
		int count = 0;
		for(ResourceCache cache : this.caches){
			int c = cache.deleteIfKey(filter);
			if(c > 0)
				count += c;
		}
		return count;
	}

	@Override
	public void cleanup(){
		for(ResourceCache cache : this.caches)
			cache.cleanup();
	}

	@Override
	public void close(){
		for(ResourceCache cache : this.caches)
			cache.close();
	}

	@Override
	public String toString(){
		StringBuilder sb = new StringBuilder("MultiLevelCache{");
		boolean f = true;
		for(ResourceCache cache : this.caches){
			if(!f)
				sb.append(", ");
			f = false;
			sb.append(cache);
		}
		return sb.append("}").toString();
	}
}
