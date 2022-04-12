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
package org.omegazero.proxyplugin.redirectinsecure;

import java.util.ArrayList;
import java.util.List;

import org.omegazero.common.config.ConfigArray;
import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.eventbus.SubscribeEvent.Priority;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http.ProxyHTTPRequest;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.ProxyUtil;

@EventBusSubscriber
public class RedirectInsecurePlugin {

	private static final Logger logger = LoggerUtil.createLogger();


	private List<String> hostnames = new ArrayList<>();

	public synchronized void configurationReload(ConfigObject config) {
		this.hostnames.clear();
		ConfigArray arr = config.optArray("hostnames");
		if(arr == null)
			return;
		for(Object o : arr){
			if(!(o instanceof String))
				throw new IllegalArgumentException("Values in 'hostnames' must be strings");
			this.hostnames.add((String) o);
		}
	}


	@SubscribeEvent(priority = Priority.HIGHEST)
	public synchronized void onHTTPRequestPre(SocketConnection downstreamConnection, ProxyHTTPRequest request, UpstreamServer userver) {
		if(!request.getScheme().equals("http"))
			return;
		String requestHostname = request.getInitialAuthority();
		if(requestHostname == null)
			return;
		boolean redirect = false;
		for(String hostname : this.hostnames){
			if(ProxyUtil.hostMatches(hostname, requestHostname)){
				redirect = true;
				break;
			}
		}
		if(redirect){
			logger.info("Redirecting HTTP to HTTPS");
			request.respond(307, new byte[0], "Location", "https://" + requestHostname + request.getInitialPath());
		}
	}
}
