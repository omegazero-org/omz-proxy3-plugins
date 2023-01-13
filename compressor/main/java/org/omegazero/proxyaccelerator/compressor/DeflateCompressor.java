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
package org.omegazero.proxyaccelerator.compressor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DeflaterOutputStream;

public class DeflateCompressor implements Compressor {


	protected ByteArrayOutputStream baos = new ByteArrayOutputStream();
	protected DeflaterOutputStream compressorStream;


	@Override
	public void init() throws IOException {
		this.compressorStream = new DeflaterOutputStream(this.baos, true);
	}

	@Override
	public byte[] compressPart(byte[] data, int len, boolean lastPart) throws IOException {
		this.compressorStream.write(data, 0, len);
		if(lastPart)
			this.compressorStream.close();
		else
			this.compressorStream.flush();
		byte[] c = this.baos.toByteArray();
		this.baos.reset();
		return c;
	}
}
