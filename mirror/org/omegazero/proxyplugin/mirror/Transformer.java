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
package org.omegazero.proxyplugin.mirror;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxyplugin.mirror.transformer.HTMLTransformerReader;

public abstract class Transformer {

	protected static final Map<String, TransformerReader> readers = new ConcurrentHashMap<>();

	protected static final Pattern ABSOLUTE_URL_PATTERN = Pattern.compile("https?://[a-zA-Z0-9\\-\\.]+(/.*)?");

	protected final String original;
	protected final String replacement;
	protected final boolean toHttp;

	public Transformer(String original, String replacement, boolean toHttp) {
		this.original = original;
		this.replacement = replacement;
		this.toHttp = toHttp;
	}


	public abstract byte[] transform(HTTPMessage request, String mimeType, byte[] data);


	protected byte[] forEachTransformable(String mimeType, byte[] data, Function<byte[], byte[]> transformer) {
		TransformerReader tr = Transformer.readers.get(mimeType);
		if(tr != null)
			return tr.forEachTransformable(data, transformer);
		else
			return data;
	}


	public static synchronized boolean registerTransformerReader(TransformerReader reader) {
		if(Transformer.readers.containsKey(reader.getMimeType()))
			return false;
		Transformer.readers.put(reader.getMimeType(), reader);
		return true;
	}


	static{
		Transformer.registerTransformerReader(new HTMLTransformerReader());
	}
}
