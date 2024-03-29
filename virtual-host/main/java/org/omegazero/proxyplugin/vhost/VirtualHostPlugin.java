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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.omegazero.common.config.ConfigArray;
import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.eventbus.SubscribeEvent.Priority;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.common.plugins.ExtendedPluginConfiguration;
import org.omegazero.http.util.HTTPStatus;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http.ProxyHTTPRequest;
import org.omegazero.proxy.net.UpstreamServer;

@EventBusSubscriber
public class VirtualHostPlugin {

	private static final Logger logger = LoggerUtil.createLogger();


	private Map<String, ConfigObject> templates = new HashMap<>();
	private VHostNode rootNode;

	@ExtendedPluginConfiguration
	public synchronized void configurationReload(ConfigObject config) throws UnknownHostException {
		this.templates.clear();
		this.rootNode = new VHostNode();

		ConfigArray hostsArray = config.optArray("hosts");
		if(hostsArray == null){
			logger.warn("hosts array was not configured, this plugin will have no effect");
			return;
		}

		ConfigObject templatesObjects = config.optObject("templates");

		for(Object obj : hostsArray){
			if(!(obj instanceof ConfigObject))
				throw new IllegalArgumentException("Elements in 'hosts' must be objects");
			ConfigObject host = (ConfigObject) obj;
			host = this.mergeVHostWithTemplateValues(templatesObjects, host);

			String path = host.optString("path", "/");
			if(path.length() < 1 || path.charAt(0) != '/')
				throw new IllegalArgumentException("path must start with a slash ('/')");

			Object hostnameObj = host.get("hostname");
			if(hostnameObj instanceof ConfigArray){
				for(Object o : (ConfigArray) hostnameObj){
					if(!(o instanceof String))
						throw new IllegalArgumentException("Elements in 'hostname' array must be strings");
					VirtualHost vh = this.getVHost((String) o, path, host);
					this.addVHost(vh);
					logger.debug("Added aggregated virtual host ", vh);
				}
			}else if(hostnameObj instanceof String){
				VirtualHost vh = this.getVHost((String) hostnameObj, path, host);
				this.addVHost(vh);
				logger.debug("Added virtual host ", vh);
			}else
				throw new IllegalArgumentException("'hostname' must either be a string or an array");
		}
	}

	private VirtualHost getVHost(String hostnamePath, String dpath, ConfigObject host) throws UnknownHostException {
		String hostname;
		String path;
		int pathI = hostnamePath.indexOf('/');
		if(pathI >= 0){
			hostname = hostnamePath.substring(0, pathI);
			path = hostnamePath.substring(pathI);
		}else{
			hostname = hostnamePath;
			path = dpath;
		}
		InetAddress addr = InetAddress.getByName(host.getString("address"));
		int plain = host.optInt("portPlain", 80);
		int tls = host.optInt("portTLS", 443);
		if(plain <= 0 && tls <= 0)
			throw new IllegalArgumentException("Upstream server " + addr + " requires either portPlain or portTLS");

		Set<String> protos = null;
		ConfigArray protosArr = host.optArray("protocols");
		if(protosArr != null){
			protos = new java.util.HashSet<>();
			for(Object o : protosArr){
				if(o instanceof String)
					protos.add((String) o);
				else
					throw new IllegalArgumentException("Values in 'protocols' must be strings");
			}
		}

		String prependPath = host.optString("prependPath", null);
		if(prependPath != null){
			if(prependPath.length() < 1)
				prependPath = null;
			else if(prependPath.charAt(0) != '/')
				throw new IllegalArgumentException("prependPath must start with a slash ('/')");
		}

		return new VirtualHost(hostname, path, host.optBoolean("preservePath", false), host.optBoolean("portWildcard", false), prependPath, addr,
				host.optInt("addressTTL", -1), plain, tls, protos, host.optBoolean("redirectInsecure", false), host.optString("hostOverride", null), host);
	}

	private ConfigObject mergeVHostWithTemplateValues(ConfigObject templatesObj, ConfigObject host) {
		String tn = host.optString("template", null);
		if(tn != null)
			return this.mergeTemplateValues(host, this.loadTemplate(templatesObj, tn));
		else
			return host;
	}

	private ConfigObject mergeTemplateValues(ConfigObject orig, ConfigObject templateObj) {
		Map<String, Object> data = orig.copyData();
		if(templateObj != null){
			for(Map.Entry<String, Object> e : templateObj.entrySet()){
				Object nValue = e.getValue();
				Object existingValue = data.get(e.getKey());
				if(existingValue != null){
					if(existingValue instanceof ConfigArray && e.getValue() instanceof ConfigArray){
						List<Object> mergedList = ((ConfigArray) existingValue).copyData();
						mergedList.addAll(((ConfigArray) e.getValue()).copyData());
						nValue = new ConfigArray(mergedList);
					}else
						continue;
				}
				data.put(e.getKey(), nValue);
			}
		}
		data.remove("template");
		return new ConfigObject(data);
	}

	private ConfigObject loadTemplate(ConfigObject templatesObj, String name) {
		return this.loadTemplate(templatesObj, name, new java.util.LinkedList<>());
	}

	private ConfigObject loadTemplate(ConfigObject templatesObj, String name, java.util.Collection<String> wtn) {
		if(this.templates.containsKey(name))
			return this.templates.get(name);
		else{
			ConfigObject template = templatesObj != null ? templatesObj.optObject(name) : null;
			if(template == null)
				throw new IllegalArgumentException("Template does not exist: " + name);
			String subtemplateName = template.optString("template", null);
			if(subtemplateName != null){
				if(wtn.contains(subtemplateName))
					throw new IllegalArgumentException("Template '" + name + "' has template property pointing to itself");
				wtn.add(subtemplateName);
				template = this.mergeTemplateValues(template, this.loadTemplate(templatesObj, subtemplateName, wtn));
			}
			this.templates.put(name, template);
			return template;
		}
	}

	private void addVHost(VirtualHost vhost) {
		String[] domainParts = vhost.getHost().split("\\.");
		VHostNode cnode = this.rootNode;
		for(int i = domainParts.length - 1; i >= 0; i--){
			String p = domainParts[i];
			if(p.length() < 1){
				throw new IllegalArgumentException("Domain part is empty in virtual host " + vhost);
			}else if(p.equals("*")){
				if(cnode.nextAny != null)
					throw new IllegalArgumentException("A wildcard is already configured for this domain: " + vhost.getHost());
				if(i > 0)
					throw new IllegalArgumentException("A wildcard must only occur at the lowest level domain name");
				cnode.nextAny = new VHostNode();
				cnode = cnode.nextAny;
			}else{
				VHostNode nnode = cnode.next.get(p);
				if(nnode == null){
					nnode = new VHostNode();
					cnode.next.put(p, nnode);
				}
				cnode = nnode;
			}
		}
		cnode.addHost(vhost);
	}


	@SubscribeEvent
	public UpstreamServer onHTTPRequestSelectServer(SocketConnection downstreamConnection, ProxyHTTPRequest request) {
		String hostname = request.getAuthority();
		String path = request.getPath();
		VirtualHost host = this.selectHost(hostname, path);
		if(host != null){
			logger.trace("Selected virtual host [", host, "] for ", hostname, path);
			return host;
		}else{
			return null;
		}
	}

	@SubscribeEvent(priority = Priority.HIGHEST)
	public void onHTTPRequestPre(SocketConnection downstreamConnection, ProxyHTTPRequest request, UpstreamServer userver) {
		if(userver instanceof VirtualHost){
			VirtualHost vhost = (VirtualHost) userver;
			if(request.getScheme().equals("http") && vhost.isRedirectInsecure()){
				logger.debug("Redirecting HTTP to HTTPS");
				request.respond(HTTPStatus.STATUS_TEMPORARY_REDIRECT, new byte[0], "Location", "https://" + request.getInitialAuthority() + request.getInitialPath());
			}
		}
	}

	@SubscribeEvent
	public void onHTTPRequest(SocketConnection downstreamConnection, ProxyHTTPRequest request, UpstreamServer userver) {
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
			if(vhost.getHostOverride() != null){
				request.setAuthority(vhost.getHostOverride());
			}
		}else{
			if(logger.debug())
				logger.debug("Request ", request.getRequestId(), " does not have an upstream server of type VirtualHost");
		}
	}


	private synchronized VirtualHost selectHost(String hostname, String path) {
		String hportStr = VirtualHost.getPortStr(hostname);

		String domain = hostname;
		if(hportStr != null)
			domain = hostname.substring(0, hostname.length() - hportStr.length() - 1);
		String[] domainParts = domain.split("\\.");
		VHostNode cnode = this.rootNode;
		for(int i = domainParts.length - 1; i >= 0; i--){
			String p = domainParts[i];
			if(p.length() < 1)
				return null;
			VHostNode nnode = cnode.next.get(p);
			if(nnode == null)
				nnode = cnode.nextAny;
			if(nnode == null)
				return null;
			cnode = nnode;
		}

		for(VirtualHost host : cnode.hosts){
			if(!host.isPortWildcard() && !Objects.equals(host.getHostPortStr(), hportStr))
				continue;
			if(path.startsWith(host.getPath()))
				return host;
		}
		return null;
	}


	private static class VHostNode {

		public Map<String, VHostNode> next = new HashMap<>();
		public VHostNode nextAny = null;
		public List<VirtualHost> hosts = new ArrayList<>();

		public void addHost(VirtualHost vhost) {
			for(int i = 0; i < this.hosts.size(); i++){
				if(vhost.getPath().length() > this.hosts.get(i).getPath().length()){
					this.hosts.add(i, vhost);
					return;
				}
			}
			this.hosts.add(vhost);
		}
	}
}
