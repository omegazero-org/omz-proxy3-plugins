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
package org.omegazero.proxyplugin.vhost;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.eventbus.SubscribeEvent.Priority;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.config.ConfigArray;
import org.omegazero.proxy.config.ConfigObject;
import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.ProxyUtil;

@EventBusSubscriber
public class VirtualHostPlugin {

	private static final Logger logger = LoggerUtil.createLogger();


	private List<VirtualHost> hosts = new ArrayList<>();

	public synchronized void configurationReload(ConfigObject config) throws UnknownHostException {
		this.hosts.clear();
		ConfigArray hostsArray = config.optArray("hosts");
		if(hostsArray == null){
			logger.warn("hosts array was not configured, this plugin will have no effect");
			return;
		}

		HashMap<String, ConfigObject> templates = new HashMap<>();
		ConfigObject templatesObjects = config.optObject("templates");
		if(templatesObjects != null){
			for(String name : templatesObjects.keySet()){
				templates.put(name, templatesObjects.getObject(name));
			}
		}

		for(Object obj : hostsArray){
			if(!(obj instanceof ConfigObject))
				throw new IllegalArgumentException("Elements in 'hosts' must be objects");
			ConfigObject host = (ConfigObject) obj;

			ConfigObject template = null;
			String templateName = host.optString("template", null);
			if(templateName != null)
				template = templates.get(templateName);

			String path = host.optString("path", "/");
			if(path.length() < 1 || path.charAt(0) != '/')
				throw new IllegalArgumentException("path must start with a slash ('/')");

			Object hostnameObj = host.get("hostname");
			if(hostnameObj instanceof ConfigArray){
				for(Object o : (ConfigArray) hostnameObj){
					if(!(o instanceof String))
						throw new IllegalArgumentException("Elements in 'hostname' array must be strings");
					VirtualHost vh = this.getVHost((String) o, path, host, template);
					this.hosts.add(vh);
					logger.debug("Added aggregated virtual host ", vh);
				}
			}else if(hostnameObj instanceof String){
				VirtualHost vh = this.getVHost((String) hostnameObj, path, host, template);
				this.hosts.add(vh);
				logger.debug("Added virtual host ", vh);
			}else
				throw new IllegalArgumentException("'hostname' must either be a string or an array");
		}
		// longer paths always overpower shorter ones, and doing this once at init is better than searching the virtual host with the longest path for every request
		this.hosts.sort((c1, c2) -> {
			return c2.getPath().length() - c1.getPath().length();
		});
	}

	private VirtualHost getVHost(String hostname, String path, ConfigObject host, ConfigObject template) throws UnknownHostException {
		String addrr = this.getTemplateValue("address", host, template, null, String.class);
		if(addrr == null)
			throw new IllegalArgumentException("'address' must be a string");
		InetAddress addr = InetAddress.getByName(addrr);
		int plain = this.getTemplateValue("portPlain", host, template, 80, Integer.class);
		int tls = this.getTemplateValue("portTls", host, template, 443, Integer.class);
		if(plain <= 0 && tls <= 0)
			throw new IllegalArgumentException("Upstream server " + addr + " requires either portPlain or portTLS");

		String prependPath = this.getTemplateValue("prependPath", host, template, null, String.class);
		if(prependPath != null){
			if(prependPath.length() < 1)
				prependPath = null;
			else if(prependPath.charAt(0) != '/')
				throw new IllegalArgumentException("prependPath must start with a slash ('/')");
		}

		return new VirtualHost(hostname, path, this.getTemplateValue("preservePath", host, template, false, Boolean.class),
				this.getTemplateValue("portWildcard", host, template, false, Boolean.class), prependPath, addr, plain, tls,
				this.getTemplateValue("redirectInsecure", host, template, false, Boolean.class), host);
	}

	private <T> T getTemplateValue(String key, ConfigObject host, ConfigObject template, T def, Class<T> type /* need this because of type erasure */) {
		Object v = host.get(key);
		if(v == null && template != null)
			v = template.get(key);
		if(v == null || !type.isAssignableFrom(v.getClass()))
			return def;
		else
			return type.cast(v);
	}


	@SubscribeEvent
	public UpstreamServer selectUpstreamServer(String hostname, String path) throws UnknownHostException {
		VirtualHost host = this.selectHost(hostname, path);
		if(host != null){
			logger.trace("Selected virtual host [", host, "] for ", hostname, path);
			return host;
		}else{
			return null;
		}
	}

	@SubscribeEvent(priority = Priority.HIGHEST)
	public void onHTTPRequestPre(SocketConnection downstreamConnection, HTTPMessage request, UpstreamServer userver) {
		if(userver instanceof VirtualHost){
			VirtualHost vhost = (VirtualHost) userver;
			if(request.getScheme().equals("http") && vhost.isRedirectInsecure()){
				logger.info("Redirecting HTTP to HTTPS");
				request.getEngine().respond(request, 307, new byte[0], "Location", "https://" + request.getOrigAuthority() + request.getOrigPath());
			}
		}
	}

	@SubscribeEvent
	public void onHTTPRequest(SocketConnection downstreamConnection, HTTPMessage request, UpstreamServer userver) {
		if(userver instanceof VirtualHost){
			VirtualHost vhost = (VirtualHost) userver;
			if(!vhost.isPreservePath()){
				String npath = request.getPath().substring(vhost.getPath().length());
				if(npath.length() < 1 || npath.charAt(0) != '/')
					npath = '/' + npath;
				request.setPath(npath);
			}
			if(vhost.getPrependPath() != null){
				request.setPath(vhost.getPrependPath() + request.getPath());
			}
		}else{
			logger.debug("Request ", request.getRequestId(), " does not have an upstream server of type VirtualHost");
		}
	}


	private synchronized VirtualHost selectHost(String hostname, String path) {
		int portStart = hostname.lastIndexOf(':');
		boolean hasPort;
		if(portStart > 0){
			hasPort = true;
			// check if string behind ':' could actually be a port (for ipv6, the end could look like ":1234]", which obviously isnt a port)
			for(int j = portStart + 1; j < hostname.length(); j++){
				char c = hostname.charAt(j);
				if(c < '0' || c > '9'){
					hasPort = false;
					break;
				}
			}
		}else
			hasPort = false;
		for(VirtualHost host : this.hosts){
			if(path.startsWith(host.getPath())){
				String chn = hostname;
				if(hasPort && host.isPortWildcard())
					chn = hostname.substring(0, portStart); // remove trailing port specified because port doesnt matter
				if(ProxyUtil.hostMatches(host.getHost(), chn))
					return host;
			}
		}
		return null;
	}
}
