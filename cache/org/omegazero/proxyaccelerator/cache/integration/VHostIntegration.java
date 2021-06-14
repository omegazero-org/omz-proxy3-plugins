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
package org.omegazero.proxyaccelerator.cache.integration;

import java.util.HashMap;
import java.util.Map;

import org.omegazero.proxy.config.ConfigObject;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxyaccelerator.cache.CacheConfig;
import org.omegazero.proxyplugin.vhost.VirtualHost;

public class VHostIntegration {

	private Map<UpstreamServer, CacheConfig> configCache = new HashMap<>();

	public CacheConfig getConfigOverride(UpstreamServer userver, CacheConfig defaultConfig) {
		if(!(userver instanceof VirtualHost))
			return null;

		CacheConfig cc = null;
		cc = this.configCache.get(userver);
		if(cc != null)
			return cc;

		VirtualHost vhost = (VirtualHost) userver;
		ConfigObject cacheConfig = vhost.getConfig().optObject("cache");
		if(cacheConfig == null)
			return null;
		cc = CacheConfig.from(cacheConfig, defaultConfig);
		this.configCache.put(userver, cc);
		return cc;
	}

	public void invalidate() {
		this.configCache.clear();
	}
}
