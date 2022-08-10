# custom-headers

Allows custom HTTP headers to be set in the configuration file.


## Configuration

Configuration ID: `customheaders`

The plugin configuration object consists of any number of arrays, where the key is a wildcard hostname for which requests to the headers should be added. The values in the arrays must be objects with the following properties:

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| direction | string | The direction of this header configuration. Either "request", "response" or "both" to include this header only in requests, responses, or both, respectively. | yes | - |
| mode | string | When to apply this header configuration. See below. | no | `"keep"` |
| key | string | The HTTP header name. Case-insensitive. | yes | - |
| value | string | The HTTP header value. | yes | - |
| separator | string | The separator for mode types "prepend" or "append". | ~ | - |
| requestPath | regex | If configured, the header will only be applied if the request path matches this regular expression (also applicable for response messages). This behavior may be inverted by prepending a single `!`. | no | `null` |
| requiredStatus | number / array(number) | If not `null`, this header object will only be applied when the response status code is or is not in this list of status codes, depending on the value of `requiredStatusWhitelist`. This property has no effect for requests. | no | `null` |
| requiredStatusWhitelist | boolean | If `true`, the response status code must be one of the status codes in `requiredStatus`; otherwise, the behavior is inverted. This property has no effect if `requiredStatus` is `null`. | no | `true` |
| requiredHeaders | object | Key-value pairs that are required for this header to be added. The key is a string, value is a regex. The value may be `null` to specify that the header must not be present. | no | (empty) |

### Mode values

These are the values that may be set for the `mode` property:
- "keep": Only set the header if it did not exist in the HTTP message.
- "replace": Replace any existing header value or add the header if it did not exist.
- "append": Append the value to an existing header value, separated by `separator`, or create the header with the given value.
- "prepend": Prepend the value to an existing header value, separated by `separator`, or create the header with the given value.
- "replaceIfExist": Same as "replace", except only when a header with the name already exists.
- "appendIfExist": Same as "append", except only when a header with the name already exists.
- "prependIfExist": Same as "prepend", except only when a header with the name already exists.

### Example

```json
{
    "*": [
        {
            "direction": "both",
            "key": "Via",
            "value": "1.1 omz-proxy",
            "mode": "append",
            "separator": ", "
        },
        {
            "direction": "response",
            "key": "X-Powered-By",
            "value": "Rainbows and Unicorns"
        }
    ],
    "*.example.com": [
        {
            "direction": "response",
            "key": "Strict-Transport-Security",
            "value": "max-age=86400",
            "mode": "replace"
        }
    ]
}
```

This configuration adds "1.1 omz-proxy" to an existing `Via` header on all HTTP messages for any host, separated by `, `, or creates the header with that value (note that *omz-proxy* already adds this header by default). It also adds a `X-Powered-By` header with the given value for all responses, if it did not exist. Finally, for all subdomains of "example.com", an additional `Strict-Transport-Security` header is added or set to the given value.

### VirtualHost plugin integration

This plugin provides an integration with the *VirtualHost* plugin. Each virtual host object may contain an array named `customheaders` which contains objects of the same format as defined above.

Note that the headers configured here are added before headers configured in the plugin configuration object.


