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
package org.omegazero.proxyplugin.servertiming;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.config.ConfigurationOption;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.plugins.ExtendedPluginConfiguration;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http.ProxyHTTPRequest;
import org.omegazero.proxy.http.ProxyHTTPResponse;
import org.omegazero.proxy.net.UpstreamServer;

@EventBusSubscriber
public class ServerTimingPlugin {

	private static final Logger logger = Logger.create();

	private static final Pattern durPattern = Pattern.compile("dur=[\\-0-9\\.e]+");


	@ConfigurationOption
	private boolean addStart = true;
	@ConfigurationOption
	private boolean subtractOriginTiming = true;

	private String headerVal;

	@ExtendedPluginConfiguration
	public synchronized void configurationReload(ConfigObject config) {
		String metricDesc = config.optString("metricDesc", null);
		String metricName = config.optString("metricName", "proxy-timing");
		this.headerVal = metricName + (metricDesc != null ? (";desc=\"" + metricDesc + "\"") : "") + ";dur=";
	}


	@SubscribeEvent
	public void onHTTPResponse(SocketConnection downstreamConnection, SocketConnection upstreamConnection, ProxyHTTPResponse response, UpstreamServer upstreamServer) {
		long time = response.getCreatedTime() - response.getOther().getCreatedTime();
		String value = response.getHeader("server-timing");
		float ut = 0;
		if(value != null && this.subtractOriginTiming){
			Matcher m = durPattern.matcher(value);
			while(m.find()){
				String numstr = m.group().substring(4);
				try{
					ut += Float.parseFloat(numstr);
				}catch(NumberFormatException e){
					logger.debug("Ignoring invalid number in server-timing dur directive: ", numstr);
				}
			}
			if(ut < time)
				time -= ut;
		}
		logger.trace("Response to ", ((ProxyHTTPRequest) response.getOther()).getRequestId(), " took ", time, "ms (+", ut, "ms)");
		String nvalue = this.headerVal + time;
		if(value != null){
			if(this.addStart)
				value = nvalue + ", " + value;
			else
				value += ", " + nvalue;
		}else{
			value = nvalue;
		}
		response.setHeader("server-timing", value);
	}
}
