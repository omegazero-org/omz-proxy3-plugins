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
package org.omegazero.proxyplugin.mirror.transformer;

import org.omegazero.proxy.http.ProxyHTTPRequest;
import org.omegazero.proxyplugin.mirror.Transformer;
import org.omegazero.proxyplugin.mirror.TransformerReplacements;

public class PathTransformer extends Transformer {


	public PathTransformer(TransformerReplacements replacements, boolean toHttp) {
		super(replacements, toHttp);
	}


	@Override
	public byte[] transform(ProxyHTTPRequest request, String mimeType, byte[] data) {
		return super.forEachTransformable(mimeType, data, (part) -> {
			String str = new String(part);
			if(ABSOLUTE_URL_PATTERN.matcher(str).matches()){
				int authStart = str.indexOf("://") + 3;
				int authEnd = str.indexOf("/", authStart);
				if(authEnd < 0)
					authEnd = str.length();
				String authority = str.substring(authStart, authEnd);
				TransformerReplacements.Replacement replacement = super.replacements.getDomainReplacement(authority);
				if(replacement == null)
					return part;
				String sub = authority.substring(0, authority.length() - replacement.getFrom().length());
				String start = str.substring(0, authStart);
				if(super.toHttp && start.startsWith("https:"))
					start = "http://";
				String path = str.substring(authEnd);
				return (start + replacement.getTo() + (sub.length() > 0 ? ("/!" + sub) : "") + (path.length() > 0 ? path : "/")).getBytes();
			}else if(str.startsWith("/")){
				String path = request.getInitialPath();
				if(!path.startsWith("/!"))
					return part;
				int subEnd = path.indexOf('/', 2);
				if(subEnd < 0)
					return part;
				String sub = path.substring(2, subEnd);
				return ("/!" + sub + str).getBytes();
			}else
				return part;
		});
	}
}
