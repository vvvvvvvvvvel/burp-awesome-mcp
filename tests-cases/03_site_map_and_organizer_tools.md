# Manual Test Cases: Site Map and Organizer Tools

## Covered tools
- `list_site_map`
- `get_site_map_by_keys`
- `send_requests_to_organizer`
- `list_organizer_items`
- `get_organizer_items_by_ids`

## Input field reference

### Shared HTTP `filter` and `serialization` objects
- `filter` fields (`list_site_map`, `list_organizer_items`):
  - `in_scope_only` (bool, default `true`)
  - `regex` (string|null)
  - `methods` (string[]|null)
  - `host_regex` (string|null)
  - `mime_types` (string[]|null)
  - `inferred_mime_types` (string[]|null)
  - `status_codes` (int[]|null)
  - `has_response` (bool|null)
  - `time_from` (string|null, ISO-8601)
  - `time_to` (string|null, ISO-8601)
- `serialization` fields (`list_site_map`, `get_site_map_by_keys`, `list_organizer_items`, `get_organizer_items_by_ids`):
  - `include_binary` (bool, default `false`)
  - `max_text_body_chars` (int, default `1024`)
  - `max_request_body_chars` (int|null)
  - `max_response_body_chars` (int|null)
  - `text_overflow_mode` (`truncate|omit`, default `omit`)
  - `max_binary_body_bytes` (int, default `65536`)
  - `regex_excerpt` (object|null):
    - `context_chars` (int, default `10`)
    - `regex` (string|null): excerpt pattern
- `fields` / `exclude_fields`:
  - item-level projection only
  - dotted paths such as `key`, `url`, `request.method`, `request.url`, `response.status_code`
  - only one of them may be non-null
  - both `null` means optimized default item shape
  - with `fields`, request/response headers and bodies are materialized only for selected branches
  - with `exclude_fields` or with both projection fields null, the optimized default non-raw shape is materialized
  - `request.raw` / `response.raw` are materialized only when explicitly requested in `fields`
  - when `serialization.regex_excerpt` is enabled, `match_context` becomes available as a normal optional projection branch
  - when `serialization.regex_excerpt` is enabled, `request.body`, `response.body`, `request.raw`, and `response.raw` must not be requested in `fields`
  - optimized default HTTP output omits root `in_scope`, `request.path`, `request.query`, `request.in_scope`, empty `response.cookies`, and duplicate stated/inferred MIME fields when they equal `response.mime_type`

Example `filter` + `serialization`:
```json
{
  "filter": {
    "in_scope_only": false,
    "methods": ["GET", "POST"],
    "mime_types": ["JSON"],
    "inferred_mime_types": ["JSON"],
    "host_regex": "example|portswigger",
    "status_codes": [200, 301, 302],
    "has_response": true
  },
  "serialization": {
    "text_overflow_mode": "omit"
  }
}
```

### `list_site_map`
- `limit` (int, default `20`): page size.
- `start_after_key` (string|null): cursor key from previous page `next`.
- `filter` (object): shared HTTP filter above.
- `serialization` (object): shared HTTP serialization above.
- `fields` / `exclude_fields`: shared item projection contract above.

### `get_site_map_by_keys`
- `keys` (string[]): exact Site Map keys from `list_site_map`.
- `serialization` (object): shared HTTP serialization above.
- `fields` / `exclude_fields`: shared item projection contract above.
- if excerpt mode is needed here, set `serialization.regex_excerpt.regex` explicitly.

### `send_requests_to_organizer`
- `items` (array): batch payload.
  - `content` (string): raw HTTP/1.1 request.
  - `target_hostname` (string)
  - `target_port` (int)
  - `uses_https` (bool)

### `list_organizer_items`
- `start_id` (int, default `0`): inclusive cursor by organizer ID.
- `id_direction` (`increasing|decreasing`, default `increasing`):
  - `increasing`: walks toward larger organizer IDs.
  - `decreasing`: walks toward smaller organizer IDs.
  - with `decreasing`, `start_id=0` starts from the last item.
- `limit` (int, default `20`): page size.
- `status` (`unknown|new|in_progress|postponed|done|ignored`[]|null): status allow-list.
- `filter` (object): shared HTTP filter above.
- `serialization` (object): shared HTTP serialization above.
- `fields` / `exclude_fields`: shared item projection contract above.

### `get_organizer_items_by_ids`
- `ids` (int[]): exact organizer item IDs.
- `serialization` (object): shared HTTP serialization above.
- `fields` / `exclude_fields`: shared item projection contract above.

## Preconditions
- Site Map is populated (visit multiple paths through Burp).
- At least one Site Map entry has response.
- Organizer has at least one item (can be created via `send_requests_to_organizer`).

## TC-SO-001 Query Site Map with defaults
- Tool: `list_site_map`
- Request:
```json
{
  "limit": 10
}
```
- Expected:
1. Response contains `total`, `results`, `next`.
2. Every result contains stable `key`.
3. Default filter applies `in_scope_only=true`.

## TC-SO-002 Query Site Map with filters and serialization
- Tool: `list_site_map`
- Request:
```json
{
  "limit": 20,
  "filter": {
    "in_scope_only": false,
    "methods": ["GET", "POST"],
    "host_regex": "example|portswigger",
    "status_codes": [200, 301, 302],
    "has_response": true
  },
  "serialization": {
    "text_overflow_mode": "omit"
  }
}
```
- Expected:
1. Returned entries satisfy all specified filter constraints.
2. Response/request bodies follow serialization config.

## TC-SO-003 Get Site Map items by keys
- Tool: `get_site_map_by_keys`
- Steps:
1. Run TC-SO-001 and collect two `results[*].key` values.
2. Request:
```json
{
  "keys": ["<key1>", "<key2>"],
  "serialization": {
    "text_overflow_mode": "omit"
  }
}
```
- Expected:
1. Response reports `requested` and `found`.
2. Valid keys return populated `item`.
3. Unknown key returns per-item `error`.

## TC-SO-004 Site Map invalid start_after_key
- Tool: `list_site_map`
- Request:
```json
{
  "limit": 5,
  "start_after_key": "deadbeef",
  "filter": {
    "in_scope_only": false
  }
}
```
- Expected:
1. Request fails with deterministic error indicating unknown `start_after_key`.

## TC-SO-005 Send request to Organizer
- Tool: `send_requests_to_organizer`
- Request:
```json
{
  "items": [
    {
      "content": "GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n",
      "target_hostname": "example.com",
      "target_port": 443,
      "uses_https": true
    }
  ]
}
```
- Expected:
1. Bulk response contains one successful result.
2. Organizer receives a new item.

## TC-SO-006 Query Organizer items
- Tool: `list_organizer_items`
- Request:
```json
{
  "start_id": 0,
  "limit": 10,
  "filter": {
    "in_scope_only": false
  },
  "serialization": {
    "text_overflow_mode": "omit"
  }
}
```
- Expected:
1. Response contains `total`, `results`, `next`.
2. Results contain `id`, `status`, `url`, `request`.

## TC-SO-007 Get Organizer items by IDs
- Tool: `get_organizer_items_by_ids`
- Steps:
1. Run TC-SO-006 and collect two organizer IDs.
2. Request:
```json
{
  "ids": [1, 2],
  "serialization": {
    "text_overflow_mode": "omit"
  }
}
```
(replace IDs with real values)
- Expected:
1. Response includes `requested`, `found`, `results`.
2. Missing ID produces per-item `error` without failing whole call.

## TC-SO-008 Query Organizer with decreasing id_direction
- Tool: `list_organizer_items`
- Goal: verify reverse walking by organizer IDs.
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

## TC-SO-009 Site Map projection
- Tool: `list_site_map`
- Request:
```json
{
  "limit": 5,
  "fields": ["key", "url", "request.method", "response.status_code"]
}
```
- Expected:
1. Response still contains `total`, `results`, `next`.
2. Each item contains only `key`, `url`, `request`, and optional `response`.
3. `request` contains only `method`.
4. `response` contains only `status_code` when present.
