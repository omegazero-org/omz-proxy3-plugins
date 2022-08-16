/*
 * Copyright (C) 2022 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxyplugin.nodnsroot;

import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.http.util.HTTPStatus;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http.ProxyHTTPRequest;
import org.omegazero.proxy.net.UpstreamServer;

@EventBusSubscriber
public class NoDNSRoot {


	private boolean redirectPermanent = false;

	public synchronized void configurationReload(ConfigObject config) {
		this.redirectPermanent = config.optBoolean("redirectPermanent", false);
	}


	@SubscribeEvent(priority = SubscribeEvent.Priority.HIGHEST)
	public void onHTTPRequestPre(SocketConnection downstreamConnection, ProxyHTTPRequest request, UpstreamServer userver) {
		String authority = request.getInitialAuthority();
		if(authority != null && authority.length() > 1 && authority.charAt(authority.length() - 1) == '.'){
			authority = authority.substring(0, authority.length() - 1);
			request.respond(this.redirectPermanent ? HTTPStatus.STATUS_PERMANENT_REDIRECT : HTTPStatus.STATUS_TEMPORARY_REDIRECT, new byte[0], "location",
					request.getInitialScheme() + "://" + authority + request.getInitialPath());
		}
	}
}
