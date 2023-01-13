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

import java.util.function.Function;

public interface TransformerReader {


	public byte[] forEachTransformable(byte[] data, Function<byte[], byte[]> transformer);

	public String getMimeType();


	public static boolean nextSequenceEqual(byte[] data, int offset, byte[] seq) {
		if(seq.length > data.length - offset)
			return false;
		for(int i = 0; i < seq.length; i++){
			if(seq[i] != data[offset + i])
				return false;
		}
		return true;
	}

	public static int indexOf(byte[] data, byte b, int offset) {
		for(int i = offset; i < data.length; i++){
			if(data[i] == b)
				return i;
		}
		return -1;
	}
}
