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
					if(s1.equals(s2)){
						f = true;
						break;
					}
				}
				if(!f)
					return false;
			}
			return true;
		}
	};
}
