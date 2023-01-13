package org.omegazero.proxyplugin.customheaders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.omegazero.common.config.ConfigArray;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxyplugin.customheaders.CustomHeadersPlugin.Header;
import org.omegazero.proxyplugin.vhost.VirtualHost;

public class VHostIntegration {

	private final Map<UpstreamServer, List<Header>> headerCache = new HashMap<>();

	public synchronized List<Header> getHostHeaders(UpstreamServer userver) {
		if(!(userver instanceof VirtualHost))
			return null;

		List<Header> h = null;
		h = this.headerCache.get(userver);
		if(h != null)
			return h;

		VirtualHost vhost = (VirtualHost) userver;
		ConfigArray headerArr = vhost.getConfig().optArray("customheaders");
		if(headerArr == null)
			return null;
		h = CustomHeadersPlugin.fromConfigArray(headerArr);
		this.headerCache.put(userver, h);
		return h;
	}

	public synchronized void invalidate() {
		this.headerCache.clear();
	}
}
