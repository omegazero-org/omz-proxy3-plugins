/*
 * Copyright (C) 2022 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxyplugin.mirror.transformer;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.function.Function;

import org.omegazero.common.logging.Logger;
import org.omegazero.proxyplugin.mirror.TransformerReader;

public class CSSTransformerReader implements TransformerReader {

	private static final Logger logger = Logger.create();

	private static final byte[] URL_FUNC = "url(".getBytes();


	@Override
	public byte[] forEachTransformable(byte[] data, Function<byte[], byte[]> transformer) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length + (data.length >> 1));
		int iterations = 0;
		int index = 0;
		int prevEnd = 0;
		int tagDepth = 0;
		int transformable = 0;
		while(index < data.length){
			iterations++;
			if(iterations > data.length)
				throw new RuntimeException("Too many iterations: " + iterations + " > " + data.length);

			if(TransformerReader.nextSequenceEqual(data, index, URL_FUNC)){
				index += URL_FUNC.length;
				int start = index;
				int end = TransformerReader.indexOf(data, (byte) ')', start);
				if(end < 0)
					continue;
				if(data[start] == '"' || data[start] == '\'')
					start++;
				if(data[end - 1] == '"' || data[end - 1] == '\'')
					end--;
				baos.write(data, prevEnd, start - prevEnd);
				transformable++;
				byte[] n = transformer.apply(Arrays.copyOfRange(data, start, end));
				baos.write(n, 0, n.length);
				index = end;
				prevEnd = index;
			}else
				index++;
		}
		baos.write(data, prevEnd, data.length - prevEnd);
		logger.debug("Found ", transformable, " transformable substrings in ", iterations, " iterations");
		return baos.toByteArray();
	}

	@Override
	public String getMimeType() {
		return "text/css";
	}
}
