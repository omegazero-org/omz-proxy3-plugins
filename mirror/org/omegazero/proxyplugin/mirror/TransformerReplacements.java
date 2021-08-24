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

import java.util.Set;

import org.omegazero.proxy.config.ConfigObject;

public class TransformerReplacements {

	private final Replacement[] replacements;

	public TransformerReplacements(Replacement[] replacements) {
		this.replacements = replacements;
	}


	public Replacement getDomainReplacement(String domain) {
		for(Replacement r : this.replacements){
			if(domain.endsWith(r.from))
				return r;
		}
		return null;
	}


	public static TransformerReplacements from(ConfigObject obj) {
		Set<String> froms = obj.keySet();
		Replacement[] replacements = new Replacement[froms.size()];
		int i = 0;
		for(String f : froms){
			replacements[i++] = new Replacement(f, obj.getString(f));
		}
		return new TransformerReplacements(replacements);
	}


	public static class Replacement {

		private final String from;
		private final String to;

		public Replacement(String from, String to) {
			this.from = from;
			this.to = to;
		}


		public String getFrom() {
			return this.from;
		}

		public String getTo() {
			return this.to;
		}
	}
}
