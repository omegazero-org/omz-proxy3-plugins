# redirect-http

Redirects HTTP requests over plaintext connections to HTTPS.


## Configuration

Configuration ID: `redirectinsecure`

The configuration only consists of an array called `hostnames`, containing wildcard hostnames for which to redirect plaintext requests.

### Example

```json
{
	"hostnames": [
		"*.example.com",
		"example.com"
	]
}
```

Redirects all plaintext requests for hostname "example.com" and all subdomains to HTTPS.

