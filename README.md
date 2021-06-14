# omz-proxy3-plugins

A collection of plugins for [omz-proxy3](https://git.omegazero.org/omz-infrastructure/omz-proxy3).

See the READMEs in the plugin directories for documentation for each plugin.


### Common Terms

Documentation of some plugins share some common terms that likely need further explanation:

##### "Wildcard Hostname"

When a string property is described as being a "wildcard hostname", this string represents a domain name (and in some cases IP address) of some host. A regular hostname may be given, for example "example.com", but this hostname can also contain a wildcard character "\*", which will match a sequence of any characters (at least one character long). For example, the wildcard hostname "\*.example.com" matches "subdomain.example.com", "other.example.com" and so on (note that this wildcard hostname will *not* match only "example.com", because a dot is treated as a regular character).

##### "Configuration ID" and "Plugin configuration object"

Each plugin has its own *plugin configuration object* in the proxy configuration file. These are each named objects in the proxy plugin configuration object, where the name of the object is the "Configuration ID" (the same as the plugin ID). For example, the proxy configuration file might look like this:

```json
{
	// ... other configuration properties ...
	"pluginConfig": {
		"somePlugin": {
			// this is the plugin configuration object of the plugin with (configuration) ID "somePlugin"
		},
		"otherPlugin": {
			// ....
		}
	}
}
```
