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

import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.config.ConfigObject;
import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxy.net.UpstreamServer;

@EventBusSubscriber
public class ServerTimingPlugin {

	private static final Logger logger = LoggerUtil.createLogger();


	private boolean addStart;

	private String headerVal;

	public synchronized void configurationReload(ConfigObject config) {
		String metricDesc = config.optString("metricDesc", null);
		String metricName = config.optString("metricName", "proxy-timing");
		this.addStart = config.optBoolean("addStart", true);

		this.headerVal = metricName + (metricDesc != null ? (";desc=\"" + metricDesc + "\"") : "") + ";dur=";
	}


	@SubscribeEvent
	public void onHTTPResponse(SocketConnection downstreamConnection, SocketConnection upstreamConnection, HTTPMessage response, UpstreamServer upstreamServer) {
		long time = response.getCreatedTime() - response.getCorrespondingMessage().getCreatedTime();
		logger.trace("Response to ", response.getCorrespondingMessage().getRequestId(), " took ", time, "ms");
		String value = response.getHeader("server-timing");
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
