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
import java.util.Objects;

import org.omegazero.common.config.ConfigObject;
import org.omegazero.proxy.net.UpstreamServer;

public class VirtualHost extends UpstreamServer {

	private final String host;
	private final String path;
	private final boolean preservePath;
	private final boolean portWildcard;
	private final String prependPath;
	private final boolean redirectInsecure;
	private final String hostOverride;
	private final ConfigObject config;

	private final String hostPortStr;

	public VirtualHost(String host, String path, boolean preservePath, boolean portWildcard, String prependPath, InetAddress upstreamAddress, int upstreamAddressTTL,
			int plainPort, int securePort, java.util.Set<String> protos, boolean redirectInsecure, String hostOverride, ConfigObject config) {
		super(upstreamAddress, upstreamAddressTTL, plainPort, securePort, protos);
		Objects.requireNonNull(host);
		this.path = Objects.requireNonNull(path);
		this.preservePath = preservePath;
		this.portWildcard = portWildcard;
		this.prependPath = prependPath;
		this.redirectInsecure = redirectInsecure;
		this.hostOverride = hostOverride;
		this.config = config;

		this.hostPortStr = getPortStr(host);
		this.host = this.hostPortStr != null ? host.substring(0, host.length() - this.hostPortStr.length() - 1) : host;
	}


	public String getHost() {
		return this.host;
	}

	public String getHostPortStr() {
		return this.hostPortStr;
	}

	public String getPath() {
		return this.path;
	}

	public boolean isPreservePath() {
		return this.preservePath;
	}

	public boolean isPortWildcard() {
		return this.portWildcard;
	}

	public String getPrependPath() {
		return this.prependPath;
	}

	public boolean isRedirectInsecure() {
		return this.redirectInsecure;
	}

	public String getHostOverride() {
		return this.hostOverride;
	}

	public ConfigObject getConfig() {
		return this.config;
	}


	@Override
	public int hashCode() {
		return super.hashCode() + this.host.hashCode() + this.path.hashCode() + (this.preservePath ? 1 : 0) + (this.portWildcard ? 2 : 0)
				+ (this.prependPath != null ? this.prependPath.hashCode() : 0);
	}

	@Override
	public boolean equals(Object o) {
		if(!(o instanceof VirtualHost) || !super.equals(o))
			return false;
		VirtualHost vhost = (VirtualHost) o;
		return this.host.equals(vhost.host) && this.path.equals(vhost.path) && this.preservePath == vhost.preservePath && this.portWildcard == vhost.portWildcard
				&& Objects.equals(this.prependPath, vhost.prependPath);
	}

	@Override
	public String toString() {
		return this.host + (this.portWildcard ? ":*" : (this.hostPortStr != null ? ":" + this.hostPortStr : "")) + this.path + " -> " + super.toString();
	}


	public static String getPortStr(String hostname) {
		int portStart = hostname.lastIndexOf(':');
		if(portStart <= 0)
			return null;
		// check if string behind ':' could actually be a port (for ipv6, the end could look like ":1234]", which obviously isnt a port)
		for(int j = portStart + 1; j < hostname.length(); j++){
			char c = hostname.charAt(j);
			if(c < '0' || c > '9')
				return null;
		}
		return hostname.substring(portStart + 1);
	}
}
