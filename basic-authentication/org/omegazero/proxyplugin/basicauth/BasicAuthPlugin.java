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
package org.omegazero.proxyplugin.basicauth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.omegazero.common.config.ConfigArray;
import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.common.eventbus.SubscribeEvent.Priority;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.http.util.HTTPStatus;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http.ProxyHTTPRequest;
import org.omegazero.proxy.http.ProxyHTTPResponse;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxyplugin.vhost.VirtualHost;

@EventBusSubscriber
public class BasicAuthPlugin {

	private static final Logger logger = LoggerUtil.createLogger();


	private final Map<String, User> users = new HashMap<>();

	public synchronized void configurationReload(ConfigObject config) {
		this.users.clear();
		ConfigArray usersArr = config.optArray("users");
		if(usersArr == null)
			return;
		int ucount = 0;
		for(Object o : usersArr){
			if(!(o instanceof ConfigObject))
				throw new IllegalArgumentException("Values in 'users' must be objects");
			ConfigObject userObj = (ConfigObject) o;
			String username = userObj.getString("username");
			User u = new User(ucount++, username, userObj.getString("password"), User.getAuthMethodNum(userObj.optString("method", "plain")));
			this.users.put(username, u);
			logger.debug("Added user '", u.username, "' (", u.userId, ")");
		}
	}


	@SubscribeEvent(priority = Priority.HIGH)
	public void onHTTPRequestPre(SocketConnection downstreamConnection, ProxyHTTPRequest request, UpstreamServer userver) {
		if(userver instanceof VirtualHost){
			VirtualHost vhost = (VirtualHost) userver;
			if(!this.isAuthenticated(request, vhost)){
				String r = request.getAuthority() + vhost.getPath();
				logger.info(request.getRequestId(), " Login to ", r);
				request.respond(HTTPStatus.STATUS_UNAUTHORIZED, new byte[0], "WWW-Authenticate", "Basic realm=\"Access to '" + r + "' is restricted\", charset=\"UTF-8\"");
			}
		}
	}

	@SubscribeEvent
	public void onHTTPResponse(SocketConnection downstreamConnection, SocketConnection upstreamConnection, ProxyHTTPResponse response, UpstreamServer userver) {
		if(userver instanceof VirtualHost){
			VirtualHost vhost = (VirtualHost) userver;
			if(vhost.getConfig().optArray("users") != null){
				response.appendHeader("vary", "authorization", ", ");
			}
		}
	}

	private synchronized boolean isAuthenticated(ProxyHTTPRequest request, VirtualHost vhost) {
		ConfigArray uarr = vhost.getConfig().optArray("users");
		if(uarr != null){
			String authRequest = request.getHeader("authorization");
			if(authRequest == null)
				return false;
			String[] parts = authRequest.split(" ");
			if(parts.length != 2 || !parts[0].toLowerCase().equals("basic"))
				return false;
			String[] auth = new String(Base64.getDecoder().decode(parts[1])).split(":", 2);
			if(auth.length != 2)
				return false;
			User loginUser = this.users.get(auth[0]);
			if(loginUser == null)
				return false;

			for(Object o : uarr){
				if(!(o instanceof String))
					continue;
				User user = this.users.get((String) o);
				if(loginUser.userId == user.userId){
					if(!loginUser.isValid(auth[1])){
						logger.warn(request.getRequestId(), " Login failed as user '", loginUser.username, "'");
						return false;
					}else{
						logger.debug(request.getRequestId(), " Authorized as '", loginUser.username, "'");
						return true;
					}
				}
			}
			return false;
		}else{
			return true;
		}
	}


	private static class User {

		public static final int AUTH_METHOD_PLAIN = 1;
		public static final int AUTH_METHOD_SHA256HEX = 2;

		private final int userId;
		private final String username;
		private final String password;
		private final int authMethod;

		public User(int userId, String username, String password, int authMethod) {
			this.userId = userId;
			this.username = username;
			this.password = password;
			this.authMethod = authMethod;
		}


		public boolean isValid(String password) {
			if(this.authMethod == AUTH_METHOD_PLAIN){
				return this.password.equals(password);
			}else if(this.authMethod == AUTH_METHOD_SHA256HEX){
				try{
					MessageDigest md = MessageDigest.getInstance("SHA-256");
					md.update(password.getBytes(StandardCharsets.UTF_8));
					byte[] hash = md.digest();
					return String.format("%064x", new BigInteger(1, hash)).equals(this.password);
				}catch(NoSuchAlgorithmException e){
					throw new RuntimeException(e);
				}
			}else
				return false;
		}


		public static int getAuthMethodNum(String str) {
			switch(str){
				case "plain":
					return AUTH_METHOD_PLAIN;
				case "sha256":
					return AUTH_METHOD_SHA256HEX;
				default:
					throw new IllegalArgumentException("'" + str + "' is not a valid authentication method");
			}
		}
	}
}
