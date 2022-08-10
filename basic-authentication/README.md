# basic-authentication

Allows very basic [HTTP authentication](https://developer.mozilla.org/en-US/docs/Web/HTTP/Authentication) to be configured for virtual hosts.

This plugin requires the `vhost` ("virtual-host") plugin.


## Configuration

Configuration ID: `basicauth`

The plugin configuration object consists of one array named `users`, containing any number of objects, each representing one user, with the following properties:

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| username | string | The username of this user. | yes | - |
| password | string | The password of this user. The format of this string is defined by `method` below. | yes | - |
| method | string | The password format. If "plain", the password is stored in plaintext. If "sha256", the password string is the hex-encoded SHA256-hash of a UTF-8 password string. | no | `"plain"` |

To actually enable authentication, an array named `users` must be added to a virtual host configuration object, which contains the usernames of the users that are allowed to access this virtual host. If this array does not exist, authentication is disabled for this virtual host.

