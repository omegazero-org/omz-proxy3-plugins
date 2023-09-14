# x-forwarded-for

Adds support to parse and add the `X-Forwarded-For` (XFF) and `X-Forwarded-Proto` (XFP) headers in requests.


## Configuration

Configuration ID: `xforwardedfor`

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| allowedClients | array(string) | Adds security by only allowing certain clients to set a XFF header. XFF headers set by clients that are not in this list of IP addresses are ignored. If not set, any client may set a XFF header. | no | `null` |
| expectedParts | array(string / array(string)) | Adds security by only allowing certain hosts be set in a request XFF header. | no | `null` |
| enforceAllowedClients | boolean | Whether to reject requests that do not match the settings set in the `allowedClients` property, instead of just ignoring the XFF header. | no | `false` |
| enforceExpectedParts | boolean | Whether to reject requests that do not match the settings set in the `expectedParts` property, instead of just ignoring the XFF header. | no | `false` |
| requireHeader | boolean | Whether to reject requests that do not have an XFF header. | no | `false` |
| includePortNumber | boolean | Whether to include the port number when adding the XFF header to forwarded requests. | no | `false` |
| enableDownstream | boolean | Enable parsing of XFF headers in requests. Note that any existing XFF header values will still be forwarded if this is `false`. | no | `true` |
| enableUpstream | boolean | Enable adding XFF headers or header values in forwarded requests. Note that any existing XFF header values will still be forwarded if this is `false`. | no | `true` |
| forwardHeader | boolean | Enable forwarding an existing XFF and XFP header value in a request. | no | `true` |
| enableForwardProto | boolean | Enable adding a XFP header in forwarded requests. | no | `true` |
| allowForwardProtoMultiple | boolean | If `true`, new values of `x-forwarded-proto` will be added to previous values (forming a list); this may not be supported by all servers. Otherwise, the header is overwritten with the newest value. To retain a previous value, set `enableForwardProto` to `false` instead. | no | `true` |

