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
import org.omegazero.common.config.ConfigurationOption;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.eventbus.SubscribeEvent.Priority;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.common.plugins.ExtendedPluginConfiguration;
import org.omegazero.http.util.HTTPStatus;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http.ProxyHTTPRequest;
import org.omegazero.proxy.util.ProxyUtil;

@EventBusSubscriber
public class XForwardedForPlugin {

	private static final Logger logger = LoggerUtil.createLogger();

	private static final String HEADER_XFF = "x-forwarded-for";
	private static final String HEADER_XFP = "x-forwarded-proto";


	@ConfigurationOption
	private java.util.List<String> allowedClients;
	@ConfigurationOption
	private boolean enforceAllowedClients = false;
	@ConfigurationOption
	private boolean enforceExpectedParts = false;
	@ConfigurationOption
	private boolean requireHeader = false;
	@ConfigurationOption
	private boolean includePortNumber = false;
	@ConfigurationOption
	private boolean enableDownstream = true;
	@ConfigurationOption
	private boolean enableUpstream = true;
	@ConfigurationOption
	private boolean forwardHeader = true;
	@ConfigurationOption
	private boolean enableForwardProto = true;
	@ConfigurationOption
	private boolean allowForwardProtoMultiple = true;

	private Object[] expectedParts;


	@ExtendedPluginConfiguration
	public synchronized void configurationReload(ConfigObject config) {
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
	}


	@SubscribeEvent(priority = Priority.HIGH)
	public void onHTTPRequestPreLog(SocketConnection downstreamConnection, ProxyHTTPRequest http) {
		if(this.enableDownstream && downstreamConnection.getApparentRemoteAddress() == downstreamConnection.getRemoteAddress())
			this.detectClientAddress(downstreamConnection, http);
		if(!this.forwardHeader){
			http.deleteHeader(HEADER_XFF);
			http.deleteHeader(HEADER_XFP);
		}
		if(this.enableUpstream)
			http.appendHeader(HEADER_XFF, addressToString((InetSocketAddress) downstreamConnection.getRemoteAddress(), this.includePortNumber));
		if(this.enableForwardProto){
			if(this.allowForwardProtoMultiple)
				http.appendHeader(HEADER_XFP, http.getScheme());
			else
				http.setHeader(HEADER_XFP, http.getScheme());
		}
	}


	private void detectClientAddress(SocketConnection downstreamConnection, ProxyHTTPRequest http) {
		String xff = http.getHeader(HEADER_XFF);
		if(xff == null){
			if(this.requireHeader){
				logger.debug("Rejecting request without X-Forwarded-For header from ", downstreamConnection.getRemoteAddress());
				http.respondError(HTTPStatus.STATUS_FORBIDDEN, "Rejected by XFF settings");
			}
			return;
		}

		boolean allowedClient = false;
		if(this.allowedClients != null){
			String clientaddr = ((InetSocketAddress) downstreamConnection.getRemoteAddress()).getAddress().getHostAddress();
			for(String s : this.allowedClients){
				if(s.equals(clientaddr)){
					allowedClient = true;
					break;
				}
			}
		}else{
			allowedClient = true;
		}
		if(!allowedClient){
			if(this.enforceAllowedClients){
				logger.debug("Rejecting request with X-Forwarded-For header from disallowed client ", downstreamConnection.getRemoteAddress());
				http.respondError(HTTPStatus.STATUS_FORBIDDEN, "Rejected by XFF settings");
			}else
				logger.debug("Ignoring X-Forwarded-For header in request from disallowed client ", downstreamConnection.getRemoteAddress());
			return;
		}

		String[] xffParts = xff.split(",");
		for(int i = 0; i < xffParts.length; i++)
			xffParts[i] = xffParts[i].trim();
		if(this.isAllowed(xffParts)){
			if(xffParts.length > 0){
				String addr = xffParts[0];
				InetSocketAddress newaddr = parseIPAddress(addr);
				if(newaddr != null){
					logger.debug(downstreamConnection.getRemoteAddress(), " is now ", newaddr);
					downstreamConnection.setApparentRemoteAddress(newaddr);
				}else
					logger.debug("Invalid IP address '", addr, "'");
			}
		}else if(this.enforceExpectedParts){
			logger.debug("Rejecting request with disallowed X-Forwarded-For header from ", downstreamConnection.getRemoteAddress());
			http.respondError(HTTPStatus.STATUS_FORBIDDEN, "Rejected by XFF settings");
		}else{
			logger.debug("Ignoring disallowed X-Forwarded-For header from ", downstreamConnection.getRemoteAddress());
		}
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
			StringBuilder sb = new StringBuilder(50);
			if(includePort)
				sb.append('[');
			for(int i = 0; i < 8; i++){
				if(i > 0){
					sb.append(':');
				}
				sb.append(Integer.toHexString(((data[i << 1] << 8) & 0xff00) | (data[(i << 1) + 1] & 0xff)));
			}
			if(includePort)
				sb.append("]:").append(address.getPort());
			return sb.toString();
		}else
			throw new IllegalArgumentException("Unexpected address length " + data.length);
	}

	private static InetSocketAddress parseIPAddress(String addr){
		if(addr.length() > 50)
			return null;
		byte[] address;
		int port;
		if(addr.indexOf('.') > 0){
			int psi = addr.indexOf(':');
			if(psi > 0){
				try{
					port = Integer.parseInt(addr.substring(psi + 1));
					addr = addr.substring(0, psi);
				}catch(NumberFormatException e){
					return null;
				}
			}else
				port = 0;
			String[] parts = addr.split("\\.");
			if(parts.length != 4)
				return null;
			address = new byte[4];
			try{
				for(int i = 0; i < 4; i++){
					int num = Integer.parseInt(parts[i]);
					if(num < 0 || num > 255)
						return null;
					address[i] = (byte) num;
				}
			}catch(NumberFormatException e){
				return null;
			}
		}else{
			if(addr.startsWith("[")){
				int ei = addr.indexOf(']');
				if(ei <= 2)
					return null;
				if(addr.length() > ei + 2 && addr.charAt(ei + 1) == ':'){
					try{
						port = Integer.parseInt(addr.substring(ei + 2));
					}catch(NumberFormatException e){
						return null;
					}
				}else
					port = 0;
				address = tryParseIPv6Bytes(addr.substring(1, ei));
			}else{
				address = tryParseIPv6Bytes(addr);
				port = 0;
			}
			if(address == null)
				return null;
		}
		try{
			return new InetSocketAddress(InetAddress.getByAddress(address), port);
		}catch(UnknownHostException e){
			return null;
		}
	}

	private static byte[] tryParseIPv6Bytes(String str){
		if(str.equals("::"))
			return new byte[16];
		String[] parts = str.split(":");
		if(parts.length < 2 || parts.length > 8)
			return null;
		byte[] address = new byte[16];
		int pi = 0;
		for(int i = 0; i < 8; i++){
			if(pi >= parts.length)
				return null;
			String p = parts[pi++];
			if(p.isEmpty()){
				if(i == 0 || i == 7){
					p = "0";
				}else if(pi - 1 == i){
					i += (8 - parts.length);
					continue;
				}else
					return null;
			}
			int num;
			try{
				num = Integer.parseInt(p, 16);
			}catch(NumberFormatException e){
				return null;
			}
			if(num < 0 || num > 0xffff)
				return null;
			address[i * 2] = (byte) (num >>> 8);
			address[i * 2 + 1] = (byte) (num);
		}
		if(pi != parts.length)
			return null;
		return address;
	}
}
