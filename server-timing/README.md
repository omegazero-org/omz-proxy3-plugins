# server-timing

Adds the upstream server response time as a metric in the `Server-Timing` response header.


## Configuration

Configuration ID: `servertiming`

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| metricDesc | string | The value of the `desc` (description) directive. If `null`, the directive will not be included. | no | `null` |
| metricName | string | The name of the metric. | no | `"proxy-timing"` |
| addStart | boolean | Whether the new metric should be appended to the front of existing metrics, instead of the back. | no | `true` |
| subtractOriginTiming | boolean | Whether any server timing reported by the upstream server should be subtracted from the measured time. | no | `true` |

### Example

```json
{
    "metricDesc": "Origin Server Response Time",
    "metricName": "osrt"
}
```

The resulting header value will be: `osrt;desc="Origin Server Response Time";dur=0`, where `0` will be replaced with the response time in milliseconds. The new value will be appended or prepended to an existing header value separated by `, `.

