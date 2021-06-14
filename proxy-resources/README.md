# proxy-resources

Ability to configure files or other resources to be served on specific URLs instead of forwarding the request.


## Configuration

Configuration ID: `proxyresources`

The plugin configuration object contains one array named `resources`, containing objects with the following properties, each representing one resource:

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| scheme | string | The scheme of the URL where this resource should be served. May be "\*" to match any scheme. | no | \* |
| hostname | string | The wildcard hostname (authority) of the URL where this resource should be served. | no | \* |
| path | string | The path on which this resource should be served. | yes | `-` |
| headers | object | Key-value pairs of additional HTTP headers that are set when serving this resource. | no | `empty` |
| contentType | string | Value of the `Content-Type` header when serving this resource. Equivalent to setting a `Content-Type` property in the `headers` object. | no | null |
| type | string | The type of this resource. Used as a shortcut for setting other properties or allows more advanced settings to be set. See below for possible values. | no | null |
| status | number | HTTP status code in the response. | no | 200 |

### Types

Below is a list of possible values for the `type` property in a resource object and their respective additional settings.

##### "redirect"

Changes the default value for `status` to 307.

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| location | string | The value of the `Location` HTTP response header, i.e. the absolute or relative URL where this resource redirects to. | yes | `-` |

##### "file"

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| filepath | string | File path of the file to be served. | yes | `-` |

##### Default (no value for `type` is provided)

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| data | string / array(number) | The data to be served. If a string is provided, the bytes of the string will be served. If a number array is provided, each number represents one byte (numbers larger than 255 will be truncated). | yes | `-` |

### Example

```json
{
	"resources": [
		{
			"type": "file",
			"path": "/favicon.ico",
			"filepath": "cat.png",
			"contentType": "image/png"
		},
		{
			"scheme": "https",
			"hostname": "example.com",
			"path": "/helloWorld",
			"data": "Hello",
			"contentType": "text/plain; charset=utf-8"
		}
	]
}
```

The first entry serves the file "cat.png" with content type "image/png" on all URLs where the path equals "/favicon.ico". The second entry serves "Hello" of type "text/plain" on this URL exactly: `https://example.com/helloWorld`.

