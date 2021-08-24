# mirror

Modifies links in documents (for example, the `src` of an `img` or other resource in HTML) during transit to be able to mirror a site to a different domain.

This is especially useful when mirroring a site to a hidden service where all resources need to be served over it but the original site links to or contains resources on a different subdomain. Here, only the original copy of the website must be stored and all links are edited automatically by this plugin.


## Configuration

Configuration ID: `mirror`

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| maxChunkSize | number | The maximum response body chunk size this plugin is willing to process, if the response body is chunked. Chunks which are larger than this maximum will cause an error to be thrown. If no transformations are being applied to the response, this value has no effect. | no | 0x1000000 `(16 MiB)` |
| transformers | array(object) | The transformations to apply to a response body. See below. | no | `empty` |

### Transformers

To actually edit the response body, the `transformers` array must contain one or more objects of the form below.

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| type | string | The type of transformation to apply. See below. | yes | `-` |
| hostname | string / array(string) | The wildcard resource hostname(s) for which this transformation should be applied to responses. | yes | `-` |
| replacements | object | Object of string key-value pairs. The hostname in the key is replaced by the hostname in the value for links that are edited. | yes | `-` |
| toHttp | boolean | Whether links that originally had the `https` URL scheme should be edited to have the `http` URL scheme instead. | no | false |

### Transformer Types

Below are possible values for the `type` option in objects in the `transformers` array.

Examples below have a configuration to replace hostname "example.com" with "example-mirror.com" (`replacements` is `{"example.com": "example-mirror.com"}`).

#### authority

This transformer only edits the *authority* part of absolute URLs in links, keeping any subdomains.

Examples:
- "example.com/page.html" -> "example-mirror.com/page.html"
- "images.example.com/logo.png" -> "images.example-mirror.com/logo.png"

#### path

This transformer edits the *authority* part of absolute URLs in links, always setting it to the replacement. If the original URL authority had any subdomain, it is instead prepended to the path, prefixed by "!". Furthermore, absolute paths (e.g. "/style.css") will also be prepended with the original subdomain of the document, if required.

This may be useful to use instead of `authority` if only a single domain name can be used as the mirror site, but the original site contains resources on multiple subdomains.

**Security Note:** This transformer should not be used to make different mutually untrusted websites available over the same origin (domain name), as it removes cross-origin isolation.

Examples:
- "example.com/page.html" -> "example-mirror.com/page.html"
- "images.example.com/logo.png" -> "example-mirror.com/!images./logo.png"
- "/style.css" -> "/style.css" (if the edited resource was served at "example-mirror.com")
- "/style.css" -> "/!subdomain./style.css" (if the edited resource was served at "example-mirror.com/!subdomain/", which originally was "subdomain.example.com")

### Transformer Readers

Transformer Readers scan the response body of a resource and extract any strings that may need to be edited. Currently, the only built-in reader is for HTML documents (mime type "text/html"), which considers `src` and `href` attributes in any tag as editable.

### Example

```json
{
	"transformers": [
		{
			"type": "path",
			"hostname": "*.hs",
			"replacements": {
				"example.com": "hiddenservicedomainname.hs"
			},
			"toHttp": true
		}
	]
}
```

The above configuration will transform this HTML document snippet to the one below it, if it was requested with the URL `http://hiddenservicedomainname.hs/!subdomain./`, which represents the mirror site of `https://subdomain.example.com`:

```html
<a href="/somepath/"><img src="https://images.example.com/logo.png" /></a>
```
```html
<a href="/!subdomain./somepath/"><img src="http://hiddenservicedomainname.hs/!images./logo.png" /></a>
```
Note that this plugin ***will not*** remove the leading `/!subdomain./` from the request path. This will need to be handled by a different plugin or server.

If instead of "path", the "authority" transformer is used, the mirror URL would be `http://subdomain.hiddenservicedomainname.hs`, and the resulting body will be:
```html
<a href="/somepath/"><img src="https://images.hiddenservicedomainname.hs/logo.png" /></a>
```


