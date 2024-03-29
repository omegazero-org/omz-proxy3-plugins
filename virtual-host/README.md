# virtual-host

Enables omz-proxy to be used for [Virtual Hosting](https://en.wikipedia.org/wiki/Virtual_hosting).


## Configuration

Configuration ID: `vhost`

### Hosts

The required array named `hosts` is a property in the plugin configuration object, containing objects with the following properties, each representing one or more virtual hosts:

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| hostname | string / array(string) | The wildcard hostname(s) of this virtual host. This string may also contain a trailing path component, which will override the path configured below (e.g. "example.com/path/"). | yes | - |
| path | string | Path base of this virtual host. The path is matched when the path in the HTTP request starts with this value. Must start with `/`. | no | `/` |
| address | string | The address of the origin server. | yes | - |
| addressTTL | number | See `upstreamServerAddressTTL` in the omz-proxy3 base configuration. | no | `-1` |
| portPlain | number | The port where the origin server is listening for plaintext HTTP requests. May be negative, in which case all requests will be forwarded to the HTTPS port below. | no | `80` |
| portTLS | number | The port where the origin server is listening for HTTPS requests. May be negative, in which case all requests will be forwarded to the plaintext HTTP port above. At least one of both ports must be given. | no | `443` |
| protocols | array(string) | A list of protocol names that the upstream server supports. Similar to `upstreamServerProtocols` in the omz-proxy3 base configuration. | no | `["http/1.1"]` |
| prependPath | string | An optional path string to prepend to the requested path before forwarding the request. | no | `null` |
| preservePath | boolean | Whether the original request path should be preserved when the `path` of this virtual host is not only `/`. Otherwise, the value of the `path` property will be cut off from the path in the request. | no | `false` |
| portWildcard | boolean | Whether a port number in the request authority parameter should be ignored. | no | `false` |
| redirectInsecure | boolean | Whether plaintext HTTP requests should be redirected to HTTPS on this virtual host. | no | `false` |
| hostOverride | string | If not `null`, the value of the `Host` header (or equivalent) will be replaced by the specified value before a request is forwarded. | no | `null` |
| template | string | The name of the template to use. See below. | no | `null` |

### Templates

Each virtual host may specify a `template` if multiple virtual hosts have similar properties.

Templates are defined in the `templates` object in the plugin configuration object. This object contains named objects, where the name of the object is the name of the template that may be used as a value of a `template` property.

If either a virtual host or template object contains a `template` property, it has the same effect as if all properties of the specified template are copied into that object. Properties that are already set are not copied (meaning: going from the virtual host object to the last template, properties set in the former objects will always override properties set in the latter objects), except arrays, which are merged.

#### Applying templates

The template name is set in the `template` property of a virtual host object. If the virtual host object is missing a property, the template object is queried for the missing property. Therefore, a virtual host object may override the properties set by the template.

A different way to image this is that the virtual host object copies all properties set in the template object, except the ones already set.

### Example

```json
{
	"templates": {
		"default": {
			"redirectInsecure": true
		},
		"userver": {
			"template": "default",
			"address": "localhost",
			"portPlain": 8080,
			"portTLS": -1
		}
	},
	"hosts": [
		{
			"hostname": "example.com",
			"template": "userver"
		},
		{
			"hostname": "api.example.com",
			"template": "userver",
			"prependPath": "/api"
		},
		{
			"hostname": "example.com",
			"path": "/wiki/",
			"template": "default",
			"address": "192.168.0.10",
			"portPlain": 80,
			"portTLS": -1
		}
	]
}
```

Requests to "example.com" will be forwarded to the origin server "localhost:8080". Requests to "api.example.com" will also be forwarded to that server, except that the origin server will perceive the requests as if all paths start with "/api/" (e.g. "api.example.com/brew-coffee" will be forwarded to the origin server as if making a request to "localhost:8080/api/brew-coffee").

The last entry will forward all requests starting with "example.com/wiki/" to a different origin server. All requests will have the leading "/wiki" component removed (e.g. "example.com/wiki/MainPage" will be forwarded to the origin server as if making a request to "192.168.2.10/MainPage"; when setting `preservePath` to `true`, this behavior will be disabled).


