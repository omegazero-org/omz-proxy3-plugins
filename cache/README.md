# cache

Proxy accelerator plugin that caches eligible resources. If and for how long a resource is cacheable is determined using the `Cache-Control` header sent by the origin server. This behavior may be changed to instead always cache resources for a set amount of time. It is also possible to purge resources using the HTTP request method PURGE or ignore revalidation requests by clients using the `Cache-Control` header.


## Configuration

Configuration ID: `cache`

### Global variables

Global variables are set directly in the plugin configuration object.

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| name | string | The name of this cache advertised in the `X-Served-By` HTTP header. The header is disabled if this value is `null`. | no | `null` |
| appendCacheName | boolean | Whether to append the cache name to the proxy name. This property only has an effect during initialization. | no | `false` |
| servedByPrefix | string | The prefix to prepend to `name` in the `X-Served-By` HTTP header. | no | `"cache-"` |
| type | string | The cache implementation to use. Currently, there are two builtin cache implementations: "lru" is a size-limited [LRU](https://en.wikipedia.org/wiki/Cache_replacement_policies#Least_recently_used_(LRU))-cache; "softreference" is a cache based on [SoftReference](https://docs.oracle.com/javase/8/docs/api/java/lang/ref/SoftReference.html)s, meaning the cache may use all available memory but entries are automatically deleted when there is memory pressure. | no | `"lru"` |
| sizeLimit | number | The maximum amount of memory the cache may use for resources in bytes. Note that this value is only a recommendation: the cache may also use more or less memory than the value specified or may ignore this value entirely. | no | half of available memory |

### Cache configuration

The default configuration for all paths is in the plugin configuration object directly (i.e. these properties are set in the same object/next to the properties above).

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| enable | boolean | Whether to enable this cache configuration. | no | `true` |
| defaultMaxAge | number | The max age (in seconds) of a resource if the origin server did not send a `Cache-Control` header. | no | `0` |
| maxAgeOverride | number | Override the `max-age` value sent by the origin server. Disabled if `-1`. | no | `-1` |
| maxAgeOverrideCacheableOnly | boolean | Only override the `max-age` value sent by the origin server to the value set in `maxAgeOverride` if the origin server advertised the resource as cacheable. | no | `false` |
| ignoreClientRefresh | boolean | Ignore any `Cache-Control` header sent by the client, and always serve resources from the cache if available. | no | `false` |
| ignoreClientRefreshIfImmutable | boolean | Applies the effect as if `ignoreClientRefresh` was set to `true` only if the response `Cache-Control` header contains the directives `immutable` or `s-immutable`. `s-immutable` has the same effect as `immutable` on shared caches, but is ignored by private caches. | no | `false` |
| maxResourceSize | number | The maximum resource size that will be attempted to be cached. | no | `0x100000` (1 MiB) |
| purgeKey | string | The required header value of the request header `X-Purge-Key` when requesting a resource to be purged using the PURGE method. If `null`, purging will be disabled; if an empty string, the header is not required and any client may purge resources from the cache. | no | `null` |
| propagatePurgeRequest | boolean | Whether to forward a PURGE request to the origin server if purging is disabled or the requested resource does not exist. | no | `false` |
| wildcardPurgeEnabled | boolean | Enables bulk resource purging using wildcards. | no | `false` |
| overrides | array(object) | Array of objects to override the default cache behavior on a specific path and hostname. The objects in this array have the same properties as this object, with some additional properties (see below). | no | (empty) |

#### Path-specific configuration

Additional properties for objects specified in the above `overrides` array.

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| inherit | boolean | Inherit properties that are not set in this configuration object from the parent object. | no | `true` |
| hostname | regex | The hostname to apply this configuration to. | no | `.*` |
| path | regex / array(regex) | The path(s) to apply this configuration to. | yes | - |

### Example

```json
{
    "name": "cdn",
    "appendCacheName": true,
    "type": "softreference",

    "defaultMaxAge": 3600,
    "ignoreClientRefresh": true,
    "overrides": [
        {
            "path": "/api/.*",
            "maxAgeOverride": 0
        }
    ]
}
```

This configuration ignores any client invalidation requests and sets a default max age of 1 hour on all paths except paths starting with "/api/", which has a `maxAgeOverride` of 0, effectively disabling any caching.

### VirtualHost plugin integration

This plugin provides an integration with the *VirtualHost* plugin. Each virtual host object may contain an object named `cache` which has the same format as the object specified in **Cache configuration** above.


## Cache behavior

### Resources that may be cached

Resources are considered cacheable if:
- the cache is enabled
- they were requested using either the GET or HEAD request method
- they were returned with any of the following response statuses: 200, 204, 301, 308, 410. This set may be expanded using the `org.omegazero.proxyaccelerator.cache.cacheableStatuses` system property consisting of a comma-separated list of numbers
- neither the request nor the response contain the `Cache-Control` directive `no-store`
- the resource size is lower than the maximum value set
- the origin server advertised the resource as cacheable in a `Cache-Control` header **OR** the cacheability was overridden by the configuration

### Purging resources

Resources may be purged using the HTTP request method PURGE, if enabled in the configuration (see above). A request header called `X-Purge-Key` must be present that matches the value set in the configuration **OR** purge authentication must be disabled, otherwise the server will respond with *401 Unauthorized*.

The resource being purged is identified by the request URL \[authority or `Host` header\] and path.
An optional additional `X-Purge-Method` header specifies the request method of the resource that should be purged.

If enabled, multiple resources may be purged at once using a wildcard (`**`) at the end of the path. All resources with paths starting with this wildcard path will be purged.

If successful, the server responds with status code 200, otherwise, if the resource does not exist, with status code 404.

### Response headers

This plugin adds several headers to the response to indicate cache status:
- `Age`: The age of the served resource (the time in seconds the resource has been in any cache), or 0 if the response was not served from cache. Note that a resource may be immediately served after it has been cached, likely also causing an `Age` value of 0.
- `X-Cache`: Either **HIT** or **MISS** if the resource was served from cache or fetched from the origin server, respectively.
- `X-Cache-Lookup`: **HIT** if the the resource is available in the cache, **MISS** otherwise
- `X-Cache-Hits`: The number of times the resource was served from the cache, or 0 if the response was not served from cache
- `X-Served-By`: The name of the cache set in the configuration. Not set if no name was provided

If any of the last four headers are already present in the response, the new value is appended to the existing value separated by `, `.


## API

### Registering a new cache implementation

Other plugins may add cache implementations identified by a short name that may then be set as a value for `type` in the global configuration.
```java
public static boolean org.omegazero.proxyaccelerator.cache.CachePlugin.registerCacheImplementation(String, Supplier<ResourceCache>);
```
Returns `true` if the new implementation was successfully registered, `false` if an implementation with the given name already exists.

### Registering a VaryComparator

VaryComparators are used to check if a response which contains a `Vary` HTTP header is allowed to be served for a request. For each header declared in the `Vary` header, the header value in the request that caused the cached response and the header value in the new request are compared using a VaryComparator. If none was registered for a header, the default is used, which only matches if both values are exactly equal.
```java
public static boolean org.omegazero.proxyaccelerator.cache.CachePlugin.registerVaryComparator(String, VaryComparator);
```
Returns `true` if a comparator was previously registered for the given header, `false` otherwise.

