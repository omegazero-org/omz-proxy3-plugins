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
package org.omegazero.proxyplugin.xforwardedfor;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import org.omegazero.common.config.ConfigArray;
import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.eventbus.SubscribeEvent.Priority;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http.HTTPCommon;
import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxy.util.ProxyUtil;

@EventBusSubscriber
public class XForwardedForPlugin {

	private static final Logger logger = LoggerUtil.createLogger();

	private static final Pattern IPV4_REGEX = Pattern.compile("([0-9]{1,3}\\.){3}[0-9]{1,3}(:[0-9]{1,5})?");
	private static final Pattern IPV6_REGEX = Pattern.compile("\\[[0-9a-zA-Z]*:[0-9a-zA-Z:]+\\](:[0-9]{1,5})?");


	private String[] allowedClients;
	private Object[] expectedParts;
	private boolean enforceAllowedClients;
	private boolean enforceExpectedParts;
	private boolean requireHeader;
	private boolean includePortNumber;
	private boolean enableDownstream;
	private boolean enableUpstream;
	private boolean forwardHeader;


	public synchronized void configurationReload(ConfigObject config) {
		ConfigArray acArr = config.optArray("allowedClients");
		if(acArr != null){
			this.allowedClients = new String[acArr.size()];
			int i = 0;
			for(Object o : acArr){
				if(!(o instanceof String))
					throw new IllegalArgumentException("Values 'allowedClients' must be strings");
				this.allowedClients[i++] = (String) o;
			}
		}
		ConfigArray epArr = config.optArray("expectedParts");
		if(epArr != null){
			this.expectedParts = new Object[epArr.size()];
			int i = 0;
			for(Object o : epArr){
				if(o instanceof String){
					this.expectedParts[i] = o;
				}else if(o instanceof ConfigArray){
					String[] a = new String[((ConfigArray) o).size()];
					int i2 = 0;
					for(Object o2 : (ConfigArray) o){
						if(!(o2 instanceof String))
							throw new IllegalArgumentException("Values in array in 'expectedParts' must be strings");
						a[i2++] = (String) o2;
					}
					this.expectedParts[i] = a;
				}else if(o == null){
					this.expectedParts[i] = null;
				}else
					throw new IllegalArgumentException("Values in 'expectedParts' must only be strings or arrays");
				i++;
			}
		}else
			this.expectedParts = null;
		this.enforceAllowedClients = config.optBoolean("enforceAllowedClients", false);
		this.enforceExpectedParts = config.optBoolean("enforceExpectedParts", false);
		this.requireHeader = config.optBoolean("requireHeader", false);
		this.includePortNumber = config.optBoolean("includePortNumber", false);
		this.enableDownstream = config.optBoolean("enableDownstream", true);
		this.enableUpstream = config.optBoolean("enableUpstream", true);
		this.forwardHeader = config.optBoolean("forwardHeader", true);
	}


	@SubscribeEvent(priority = Priority.HIGH)
	public void onHTTPRequestPreLog(SocketConnection downstreamConnection, HTTPMessage http) {
		String xff = http.getHeader("x-forwarded-for");
		if(this.enableDownstream && downstreamConnection.getApparentRemoteAddress() == downstreamConnection.getRemoteAddress())
			this.detectClientAddress(downstreamConnection, http, xff);
		if(!this.forwardHeader && xff != null){
			http.deleteHeader("x-forwarded-for");
			xff = null;
		}
		if(this.enableUpstream)
			this.forwardClientAddress(downstreamConnection, http, xff);
	}


	private void detectClientAddress(SocketConnection downstreamConnection, HTTPMessage http, String xff) {
		if(xff == null){
			if(this.requireHeader){
				logger.warn("Rejecting request without X-Forwarded-For header from ", downstreamConnection.getRemoteAddress());
				http.getEngine().respondError(http, HTTPCommon.STATUS_FORBIDDEN, "Forbidden", "Rejected by XFF settings.");
			}
			return;
		}

		boolean allowedClient = false;
		if(this.allowedClients != null){
			for(String s : this.allowedClients){
				if(s.equals(((InetSocketAddress) downstreamConnection.getRemoteAddress()).getAddress().getHostAddress())){
					allowedClient = true;
					break;
				}
			}
		}else{
			allowedClient = true;
		}
		if(!allowedClient){
			if(this.enforceAllowedClients){
				logger.warn("Rejecting request with X-Forwarded-For header from disallowed client ", downstreamConnection.getRemoteAddress());
				http.getEngine().respondError(http, HTTPCommon.STATUS_FORBIDDEN, "Forbidden", "Rejected by XFF settings.");
			}else
				logger.warn("Ignoring X-Forwarded-For header in request from disallowed client ", downstreamConnection.getRemoteAddress());
			return;
		}

		String[] xffParts = xff.split(",");
		for(int i = 0; i < xffParts.length; i++)
			xffParts[i] = xffParts[i].trim();
		if(this.isAllowed(xffParts)){
			if(xffParts.length > 0){
				String part = xffParts[0];
				String address;
				int portIndex;
				if(IPV4_REGEX.matcher(part).matches()){
					portIndex = part.indexOf(':');
				}else if(IPV6_REGEX.matcher(part).matches()){
					portIndex = part.indexOf(':', part.indexOf(']'));
				}else
					return;
				int port = 0;
				if(portIndex > 0){
					address = part.substring(0, portIndex);
					port = Integer.parseInt(part.substring(portIndex + 1));
				}else{
					address = part;
				}
				try{
					InetSocketAddress newaddr = new InetSocketAddress(InetAddress.getByName(address), port);
					logger.debug(downstreamConnection.getRemoteAddress(), " is now ", newaddr);
					downstreamConnection.setApparentRemoteAddress(newaddr);
				}catch(UnknownHostException e){
					logger.warn("Invalid IP address '", address, "': ", e.toString());
				}
			}
		}else if(this.enforceExpectedParts){
			logger.warn("Rejecting request with disallowed X-Forwarded-For header from ", downstreamConnection.getRemoteAddress());
			http.getEngine().respondError(http, HTTPCommon.STATUS_FORBIDDEN, "Forbidden", "Rejected by XFF settings.");
		}else{
			logger.warn("Ignoring disallowed X-Forwarded-For header from ", downstreamConnection.getRemoteAddress());
		}
	}

	private void forwardClientAddress(SocketConnection downstreamConnection, HTTPMessage http, String xff) {
		String naddr = addressToString((InetSocketAddress) downstreamConnection.getRemoteAddress(), this.includePortNumber);
		if(xff != null)
			naddr = xff + ", " + naddr;
		http.setHeader("x-forwarded-for", naddr);
	}


	private synchronized boolean isAllowed(String[] xffParts) {
		if(this.expectedParts == null)
			return true;
		if(this.expectedParts.length != xffParts.length)
			return false;
		for(int i = 0; i < this.expectedParts.length; i++){
			String part = xffParts[i];
			Object v = this.expectedParts[i];
			if(v instanceof String[]){
				boolean optAllowed = false;
				String[] opts = (String[]) v;
				for(int j = 0; j < opts.length; j++){
					if(ProxyUtil.hostMatches(opts[j], part)){
						optAllowed = true;
						break;
					}
				}
				if(!optAllowed)
					return false;
			}else if(v instanceof String){
				if(!ProxyUtil.hostMatches((String) v, part))
					return false;
			}else if(v == null){
				continue;
			}else
				throw new RuntimeException("Invalid type in expectedParts array: " + v.getClass().getName());
		}
		return true;
	}


	private static String addressToString(InetSocketAddress address, boolean includePort) {
		byte[] data = address.getAddress().getAddress();
		if(data.length == 4){
			return (data[0] & 0xff) + "." + (data[1] & 0xff) + "." + (data[2] & 0xff) + "." + (data[3] & 0xff) + (includePort ? (":" + address.getPort()) : "");
		}else if(data.length == 16){
			StringBuilder sb = new StringBuilder(39);
			sb.append('[');
			for(int i = 0; i < 8; i++){
				if(i > 0){
					sb.append(':');
				}
				sb.append(Integer.toHexString(((data[i << 1] << 8) & 0xff00) | (data[(i << 1) + 1] & 0xff)));
			}
			sb.append(']');
			if(includePort)
				sb.append(':').append(address.getPort());
			return sb.toString();
		}else
			throw new IllegalArgumentException("Unexpected address length " + data.length);
	}
}
