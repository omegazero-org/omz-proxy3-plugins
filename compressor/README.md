# compressor

Compresses response bodies to decrease resource transfer size.


## Configuration

Configuration ID: `compressor`

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| enabledMimeTypes | array(string) | The list of resource MIME types to compress. | no | `empty` |
| preferredCompressor | string | The preferred compression algorithm to use, as set in the `Content-Encoding` header. "gzip" and "deflate" are available by default. If not set, the first supported algorithm presented by the client in the `Accept-Encoding` header is selected. | no | null |
| onlyIfChunked | boolean | Only compress the response body if it was already chunked, because this plugin requires (and will enable) chunked response bodies, which effectively removes any `Content-Length` header. | no | false |
| onlyIfNoEncoding | boolean | Only compress the response body if no encoding (most likely compression) was applied already (i.e. the `Content-Encoding` header is not present). | no | true |

