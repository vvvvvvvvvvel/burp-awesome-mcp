# Manual Test Cases: WebSocket History Tools

## Covered tools
- `list_proxy_websocket_history`
- `get_proxy_websocket_messages_by_ids`

## Input field reference

### `list_proxy_websocket_history`
- `start_id` (int, default `0`): inclusive cursor by stable WebSocket message ID; negative values are allowed.
- `id_direction` (`increasing|decreasing`, default `increasing`):
  - `increasing`: walks toward larger IDs.
  - `decreasing`: walks toward smaller IDs.
  - with `decreasing`, `start_id=0` starts from the last item.
- `limit` (int, default `20`): number of returned records.
- `filter` (object):
  - `in_scope_only` (bool, default `true`): only messages whose upgrade request is in scope.
  - `regex` (string|null): regex over message payload/metadata.
  - `direction` (`client_to_server|server_to_client`[]|null): direction allow-list.
  - `web_socket_ids` (int[]|null): filter by specific WebSocket channels.
  - `host_regex` (string|null): regex for upgrade-request host.
  - `listener_ports` (int[]|null): filter by listener port.
  - `has_edited_payload` (bool|null): require edited payload presence/absence.
  - `time_from` (string|null): ISO-8601 lower bound (inclusive).
  - `time_to` (string|null): ISO-8601 upper bound (inclusive).
- `serialization` (object):
  - `include_binary` (bool, default `false`)
  - `include_edited_payload` (bool, default `false`)
  - `max_text_payload_chars` (int, default `4000`)
  - `max_binary_payload_bytes` (int, default `65536`)

Example `filter` + `serialization`:
```json
{
  "filter": {
    "in_scope_only": false,
    "direction": ["client_to_server"],
    "web_socket_ids": [11, 12],
    "host_regex": "example|portswigger",
    "has_edited_payload": false,
    "time_from": "2026-03-01T00:00:00Z",
    "time_to": "2026-03-31T23:59:59Z"
  },
  "serialization": {
    "include_binary": false,
    "include_edited_payload": true,
    "max_text_payload_chars": 2000,
    "max_binary_payload_bytes": 65536
  }
}
```

### `get_proxy_websocket_messages_by_ids`
- `ids` (int[]): exact WebSocket message IDs to fetch.
- `serialization` (object): same fields and defaults as above.

## Preconditions
- Burp has captured WebSocket traffic (client->server and server->client).
- At least one message has text payload.
- Optional: at least one message has edited payload in Burp.

## TC-WS-001 Query WebSocket history with defaults
- Tool: `list_proxy_websocket_history`
- Request:
```json
{
  "start_id": 0,
  "limit": 10
}
```
- Expected:
1. Response contains `total`, `results`, `next`.
2. By default only in-scope upgrade requests are returned (`filter.in_scope_only=true`).
3. Every item has `id`, `web_socket_id`, `direction`, `payload`, `upgrade_request`.

## TC-WS-002 Query with structured filter
- Tool: `list_proxy_websocket_history`
- Request:
```json
{
  "start_id": 0,
  "limit": 20,
  "filter": {
    "in_scope_only": false,
    "direction": ["client_to_server"],
    "host_regex": "example|portswigger",
    "has_edited_payload": false
  },
  "serialization": {
    "include_binary": false,
    "include_edited_payload": false,
    "max_text_payload_chars": 2000,
    "max_binary_payload_bytes": 65536
  }
}
```
- Expected:
1. Returned directions are only `client_to_server`.
2. Host constraint is respected.
3. Payload serialization obeys text/binary limits.

## TC-WS-003 Regex filter and next page
- Tool: `list_proxy_websocket_history`
- Steps:
1. Send request with regex that matches known payload keyword.
2. If `next` is non-null, call tool again with exact `next` object.
- Sample request:
```json
{
  "start_id": 0,
  "limit": 5,
  "filter": {
    "in_scope_only": false,
    "regex": "subscribe|ping"
  }
}
```
- Expected:
1. First page contains only messages matching regex.
2. Next page starts at correct continuation point.

## TC-WS-004 Invalid regex handling
- Tool: `list_proxy_websocket_history`
- Request:
```json
{
  "start_id": 0,
  "limit": 5,
  "filter": {
    "regex": "(ws"
  }
}
```
- Expected:
1. `isError=true`.
2. Error clearly indicates regex compilation failure.

## TC-WS-005 Get explicit WebSocket message IDs
- Tool: `get_proxy_websocket_messages_by_ids`
- Steps:
1. Run TC-WS-001 and collect two IDs.
2. Request:
```json
{
  "ids": [201, 202],
  "serialization": {
    "include_binary": false,
    "include_edited_payload": true,
    "max_text_payload_chars": 1000,
    "max_binary_payload_bytes": 4096
  }
}
```
(replace IDs with real values)
- Expected:
1. Response contains `requested`, `found`, `results`.
2. Each result item includes either `item` or `error`.
3. Scope filter is not applied in this tool.

## TC-WS-006 Query with decreasing id_direction
- Tool: `list_proxy_websocket_history`
- Goal: verify reverse walking by stable message IDs.
- Request:
```json
{
  "start_id": 0,
  "id_direction": "decreasing",
  "limit": 5,
  "filter": {
    "in_scope_only": false
  }
}
```
- Expected:
1. No validation/runtime error.
2. If two or more items are returned, IDs are strictly descending.
3. `next` (if non-null) keeps `id_direction: "decreasing"`.
