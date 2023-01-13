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
package org.omegazero.proxyaccelerator.cache;

@FunctionalInterface
public interface VaryComparator {

	public boolean semanticallyEquivalent(String value1, String value2);


	public static final VaryComparator EQUALS_COMPARATOR = new VaryComparator(){

		@Override
		public boolean semanticallyEquivalent(String value1, String value2) {
			return (value1 == value2) || (value1 != null && value1.equals(value2));
		}
	};

	public static final VaryComparator DIRECTIVE_LIST_COMPARATOR = new VaryComparator(){

		@Override
		public boolean semanticallyEquivalent(String value1, String value2) {
			return VaryComparator.directiveListCompare(value1, value2, false);
		}
	};

	public static final VaryComparator DIRECTIVE_LIST_COMPARATOR_CASE_SENSITIVE = new VaryComparator(){

		@Override
		public boolean semanticallyEquivalent(String value1, String value2) {
			return VaryComparator.directiveListCompare(value1, value2, true);
		}
	};

	static boolean directiveListCompare(String value1, String value2, boolean caseSensitive) {
		if(value1 == value2) // both are null
			return true;
		if(value1 == null || value2 == null) // either is missing
			return false;
		String[] a1 = value1.split(",");
		String[] a2 = value2.split(",");
		if(a1.length != a2.length)
			return false;
		for(int i = 0; i < a1.length; i++){
			a1[i] = a1[i].trim();
			a2[i] = a2[i].trim();
		}
		for(String s1 : a1){
			boolean f = false;
			for(String s2 : a2){
				if(caseSensitive ? s1.equals(s2) : s1.equalsIgnoreCase(s2)){
					f = true;
					break;
				}
			}
			if(!f)
				return false;
		}
		return true;
	}
}
