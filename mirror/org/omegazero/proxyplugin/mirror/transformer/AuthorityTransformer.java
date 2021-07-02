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

import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxyplugin.mirror.Transformer;

public class AuthorityTransformer extends Transformer {


	public AuthorityTransformer(String original, String replacement, boolean toHttp) {
		super(original, replacement, toHttp);
	}


	@Override
	public byte[] transform(HTTPMessage request, String mimeType, byte[] data) {
		return super.forEachTransformable(mimeType, data, (part) -> {
			String str = new String(part);
			if(!ABSOLUTE_URL_PATTERN.matcher(str).matches())
				return part;
			int authStart = str.indexOf("://") + 3;
			int authEnd = str.indexOf("/", authStart);
			if(authEnd < 0)
				authEnd = str.length();
			String authority = str.substring(authStart, authEnd);
			if(!authority.endsWith(super.original))
				return part;
			authority = authority.substring(0, authority.length() - super.original.length()) + super.replacement;
			String start = str.substring(0, authStart);
			if(super.toHttp && start.startsWith("https:"))
				start = "http://";
			return (start + authority + str.substring(authEnd)).getBytes();
		});
	}
}
