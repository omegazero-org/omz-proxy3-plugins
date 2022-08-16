# no-dns-root

If the request URL authority (`Host` header in HTTP/1) of a request ends with a dot, representing the DNS root zone, a response is sent back by this plugin redirecting the client to the same URL without the ending dot in the authority part.

The reason for doing this is that browsers will usually allow visiting a website with and without the dot at the end, and will also send the URL authority with or without the dot. Since explicitly adding the dot is unconventional, most websites are not configured to properly handle this case, and problems can arise when, for example, using virtual hosts or strict Content Security Policies.


## Configuration

Configuration ID: `nodnsroot`

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| redirectPermanent | boolean | Send a permanent redirect (status 308) instead of a temporary redirect (status 307). | no | `false` |

