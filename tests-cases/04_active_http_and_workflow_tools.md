# Manual Test Cases: Active HTTP and Workflow Tools

## Covered tools
- `send_http1_requests`
- `send_http2_requests`
- `create_repeater_tabs`
- `send_requests_to_intruder`
- `send_requests_to_intruder_template`

## Input field reference

### Shared HTTP serialization object
- `include_headers` (bool, default `true`)
- `include_request_body` (bool, default `true`)
- `include_response_body` (bool, default `true`)
- `include_raw_request` (bool, default `false`)
- `include_raw_response` (bool, default `false`)
- `include_binary` (bool, default `false`)
- `max_text_body_chars` (int, default `1024`)
- `max_request_body_chars` (int|null)
- `max_response_body_chars` (int|null)
- `text_overflow_mode` (`truncate|omit`, default `omit`)
- `max_binary_body_bytes` (int, default `65536`)

### Shared `request_options` object (`send_http1_requests`, `send_http2_requests`)
- `http_mode` (string|null): transport mode (`http_1`, `http_2`, `http_2_ignore_alpn` depending on tool).
- `connection_id` (string|null): reuse connection context if supported.
- `redirection_mode` (string|null): redirect behavior.
- `response_timeout_ms` (long|null): per-request timeout.
- `server_name_indicator` (string|null): explicit TLS SNI hostname.
- `upstream_tls_verification` (bool, default `false`): enable/disable upstream cert verification.

Request options example:
```json
{
  "request_options": {
    "http_mode": "http_2",
    "response_timeout_ms": 15000,
    "upstream_tls_verification": false
  }
}
```

### `send_http1_requests`
- `items` (array):
  - `content` (string): raw HTTP/1.1 request bytes.
  - `target_hostname` (string)
  - `target_port` (int)
  - `uses_https` (bool)
- `request_options` (object|null): shared object above.
- `serialization` (object): shared HTTP serialization above.
- `parallel` (bool, default `false`): process list in parallel.
- `parallel_rps` (int, default `10`): throughput cap for parallel mode.

### `send_http2_requests`
- `items` (array):
  - `pseudo_headers` (object): required HTTP/2 pseudo-headers (for example `:method`, `:scheme`, `:authority`, `:path`).
  - `headers` (object): regular headers map.
  - `headers_list` (`[{name,value}]`|null): optional ordered/duplicate-safe header list.
  - `request_body` (string)
  - `target_hostname` (string)
  - `target_port` (int)
  - `uses_https` (bool)
- `request_options` (object|null): shared object above (for this tool, `http_mode` should be `http_2` or `http_2_ignore_alpn`).
- `serialization` (object): shared HTTP serialization above.
- `parallel` (bool, default `false`)
- `parallel_rps` (int, default `10`)

### `create_repeater_tabs` / `send_requests_to_intruder`
- `items` (array):
  - `content` (string): raw HTTP request.
  - `target_hostname` (string)
  - `target_port` (int)
  - `uses_https` (bool)
  - `tab_name` (string|null): optional tab title.

### `send_requests_to_intruder_template`
- `items` (array):
  - same fields as `send_requests_to_intruder`
  - `insertion_points` (`[{start,end}]`|null): explicit byte ranges; `end` must be greater than `start`.
  - `generation_mode` (string|null): optional insertion-point generation mode.

## Preconditions
- Network access to test target (for example `example.com`).
- Burp Intruder available.
- Burp Repeater available.

## TC-AW-001 send_http1_requests basic single request
- Tool: `send_http1_requests`
- Request:
```json
{
  "items": [
    {
      "content": "GET / HTTP/1.1\\r\\nHost: example.com\\r\\nConnection: close\\r\\n\\r\\n",
      "target_hostname": "example.com",
      "target_port": 443,
      "uses_https": true
    }
  ],
  "parallel": false,
  "serialization": {
    "include_headers": true,
    "include_response_body": true,
    "text_overflow_mode": "omit"
  }
}
```
- Expected:
1. Bulk result returns `ok=true`.
2. Result includes `status_code`, request summary, and response summary.

## TC-AW-002 send_http1_requests parallel mode
- Tool: `send_http1_requests`
- Request:
```json
{
  "items": [
    {
      "content": "GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n",
      "target_hostname": "example.com",
      "target_port": 443,
      "uses_https": true
    },
    {
      "content": "GET /robots.txt HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n",
      "target_hostname": "example.com",
      "target_port": 443,
      "uses_https": true
    }
  ],
  "parallel": true,
  "parallel_rps": 5
}
```
- Expected:
1. Both items processed.
2. Order in response corresponds to input order.
3. No unhandled exceptions.

## TC-AW-003 send_http2_requests basic request
- Tool: `send_http2_requests`
- Request:
```json
{
  "items": [
    {
      "pseudo_headers": {
        ":method": "GET",
        ":scheme": "https",
        ":authority": "example.com",
        ":path": "/"
      },
      "headers": {
        "user-agent": "awesome-mcp-test"
      },
      "request_body": "",
      "target_hostname": "example.com",
      "target_port": 443,
      "uses_https": true
    }
  ],
  "request_options": {
    "http_mode": "http_2"
  }
}
```
- Expected:
1. Request succeeds.
2. HTTP/2 response summary is returned.

## TC-AW-004 send_http2_requests invalid mode rejection
- Tool: `send_http2_requests`
- Request:
```json
{
  "items": [
    {
      "pseudo_headers": {
        ":method": "GET",
        ":scheme": "https",
        ":authority": "example.com",
        ":path": "/"
      },
      "headers": {},
      "request_body": "",
      "target_hostname": "example.com",
      "target_port": 443,
      "uses_https": true
    }
  ],
  "request_options": {
    "http_mode": "http_1"
  }
}
```
- Expected:
1. Per-item result is `ok=false`.
2. Error explains allowed modes (`http_2` / `http_2_ignore_alpn`).

## TC-AW-005 create_repeater_tabs
- Tool: `create_repeater_tabs`
- Request:
```json
{
  "items": [
    {
      "content": "GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n",
      "target_hostname": "example.com",
      "target_port": 443,
      "uses_https": true,
      "tab_name": "MCP Repeater Smoke"
    }
  ]
}
```
- Expected:
1. Bulk response success.
2. New tab appears in Burp Repeater with expected name/content.

## TC-AW-006 send_requests_to_intruder
- Tool: `send_requests_to_intruder`
- Request:
```json
{
  "items": [
    {
      "content": "GET /search?q=test HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n",
      "target_hostname": "example.com",
      "target_port": 443,
      "uses_https": true,
      "tab_name": "MCP Intruder Smoke"
    }
  ]
}
```
- Expected:
1. Bulk response success.
2. Intruder tab receives the request.

## TC-AW-007 send_requests_to_intruder_template with explicit insertion points
- Tool: `send_requests_to_intruder_template`
- Request:
```json
{
  "items": [
    {
      "content": "GET /search?q=test HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n",
      "target_hostname": "example.com",
      "target_port": 443,
      "uses_https": true,
      "tab_name": "MCP Intruder Template",
      "insertion_points": [
        {
          "start": 14,
          "end": 18
        }
      ]
    }
  ]
}
```
- Expected:
1. Bulk response success.
2. Intruder receives template with insertion point.

## TC-AW-008 send_requests_to_intruder_template invalid insertion range
- Tool: `send_requests_to_intruder_template`
- Request:
```json
{
  "items": [
    {
      "content": "GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n",
      "target_hostname": "example.com",
      "target_port": 443,
      "uses_https": true,
      "insertion_points": [
        {
          "start": 30,
          "end": 10
        }
      ]
    }
  ]
}
```
- Expected:
1. Tool returns error for invalid range (`end > start` required).
