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

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.function.Function;

import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.proxyplugin.mirror.TransformerReader;

public class HTMLTransformerReader implements TransformerReader {

	private static final Logger logger = LoggerUtil.createLogger();

	private static final byte[] HREF_ATTR = "href=".getBytes();
	private static final byte[] SRC_ATTR = "src=".getBytes();
	private static final int SKIP_LEN = Math.min(HREF_ATTR.length, SRC_ATTR.length);


	@Override
	public byte[] forEachTransformable(byte[] data, Function<byte[], byte[]> transformer) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length + data.length / 2); // 50% bigger than original because replacement URLs are likely longer
		int iterations = 0;
		int index = 0;
		int prevEnd = 0;
		int tagDepth = 0;
		int transformable = 0;
		while(index < data.length){
			iterations++;
			if(iterations > data.length)
				throw new RuntimeException("Too many iterations: " + iterations + " > " + data.length);

			if(data[index] == '<' && index < data.length - 1 && data[index + 1] != '/') // skip closing tags
				tagDepth++;
			else if(data[index] == '>'){
				tagDepth--;
				if(tagDepth < 0)
					tagDepth = 0;
			}

			if(tagDepth > 0){
				if(TransformerReader.nextSequenceEqual(data, index, HREF_ATTR) || TransformerReader.nextSequenceEqual(data, index, SRC_ATTR)){
					index += SKIP_LEN;
					int start = TransformerReader.indexOf(data, (byte) '"', index);
					if(start < 0)
						continue;
					start++;
					int end = TransformerReader.indexOf(data, (byte) '"', start);
					if(end < 0)
						continue;
					baos.write(data, prevEnd, start - prevEnd);
					transformable++;
					byte[] n = transformer.apply(Arrays.copyOfRange(data, start, end));
					baos.write(n, 0, n.length);
					index = end;
					prevEnd = index;
				}else
					index++;
			}else{
				index++;
			}
		}
		baos.write(data, prevEnd, data.length - prevEnd);
		logger.debug("Found ", transformable, " transformable substrings in ", iterations, " iterations");
		return baos.toByteArray();
	}

	@Override
	public String getMimeType() {
		return "text/html";
	}
}
