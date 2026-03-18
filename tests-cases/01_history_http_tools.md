# Manual Test Cases: HTTP History Tools

## Covered tools
- `list_proxy_http_history`
- `get_proxy_http_history_by_ids`
- `summarize_http_history_cookies`
- `summarize_http_history_auth_headers`

## Input field reference

### `list_proxy_http_history`
- `start_id` (int, default `0`): inclusive cursor by stable Burp history ID; negative values are allowed (tail-style addressing).
- `id_direction` (`increasing|decreasing`, default `increasing`):
  - `increasing`: walks toward larger IDs.
  - `decreasing`: walks toward smaller IDs.
  - with `decreasing`, `start_id=0` starts from the last item.
- `limit` (int, default `20`): number of returned records.
- `filter` (object):
  - `in_scope_only` (bool, default `true`): include only in-scope entries.
  - `regex` (string|null): regex over request/response content and annotations.
  - `methods` (string[]|null): HTTP method allow-list (for example `["GET","POST"]`).
  - `host_regex` (string|null): regex matched against host.
  - `mime_types` (string[]|null): response MIME allow-list.
  - `inferred_mime_types` (string[]|null): strict allow-list for `response.inferred_mime_type`.
  - `status_codes` (int[]|null): response status allow-list.
  - `listener_ports` (int[]|null): allow-list of Burp Proxy listener ports.
  - `has_response` (bool|null): require response presence/absence.
  - `time_from` (string|null): ISO-8601 lower bound (inclusive).
  - `time_to` (string|null): ISO-8601 upper bound (inclusive).
- `serialization` (object):
  - `include_binary` (bool, default `false`)
  - `max_text_body_chars` (int, default `1024`)
  - `max_request_body_chars` (int|null): request-body-specific override.
  - `max_response_body_chars` (int|null): response-body-specific override.
  - `text_overflow_mode` (`truncate|omit`, default `omit`)
  - `max_binary_body_bytes` (int, default `65536`)
  - `regex_excerpt` (object|null):
    - `context_chars` (int, default `10`)
    - `regex` (string|null): excerpt pattern
- `fields` (string[]|null): include only listed item-relative paths such as `id`, `time`, `listener_port`, `request.method`, `request.url`, `response.status_code`.
- `exclude_fields` (string[]|null): exclude listed item-relative paths from otherwise optimized default items.
- only one of `fields` or `exclude_fields` may be non-null; both `null` means optimized default item shape.
- with `fields`, request/response headers and bodies are materialized only for selected branches.
- with `exclude_fields` or with both projection fields null, the optimized default non-raw shape is materialized.
- `request.raw` / `response.raw` are materialized only when explicitly requested in `fields`.
- when `serialization.regex_excerpt` is enabled, `match_context` becomes available as a normal optional projection branch.
- when `serialization.regex_excerpt` is enabled:
  - `request.body`, `response.body`, `request.raw`, and `response.raw` must not be requested in `fields`
- optimized default HTTP output omits `listener_port`, `edited`, `request.path`, `request.query`, `request.in_scope`, empty `response.cookies`, and duplicate stated/inferred MIME fields when they equal `response.mime_type`

Example `filter` + `serialization`:
```json
{
  "filter": {
    "in_scope_only": false,
    "methods": ["POST"],
    "mime_types": ["JSON"],
    "inferred_mime_types": ["JSON"],
    "status_codes": [200, 401, 403],
    "listener_ports": [8080, 8081],
    "host_regex": "example|portswigger",
    "has_response": true,
    "time_from": "2026-03-01T00:00:00Z",
    "time_to": "2026-03-31T23:59:59Z"
  },
  "serialization": {
    "include_binary": false,
    "max_text_body_chars": 1024,
    "text_overflow_mode": "omit",
    "max_binary_body_bytes": 65536
  }
}
```

### `get_proxy_http_history_by_ids`
- `ids` (int[]): exact Burp history IDs to fetch.
- `serialization` (object): same fields and defaults as above.
- `fields` / `exclude_fields`: same projection contract as `list_proxy_http_history`.
- if excerpt mode is needed here, set `serialization.regex_excerpt.regex` explicitly.

### `summarize_http_history_cookies` / `summarize_http_history_auth_headers`
- `limit` (int, default `50`): number of history entries scanned in the selected window.
- `offset` (int, default `0`): start offset in sorted history.
- `order` (`asc|desc`, default `desc`): scan direction.
- `in_scope_only` (bool, default `true`): whether to scan only in-scope history.
- `regex` (string|null): optional pre-filter regex before aggregation.

Aggregation request example:
```json
{
  "limit": 50,
  "offset": 0,
  "order": "desc",
  "in_scope_only": false,
  "regex": "session|auth|token"
}
```

## Preconditions
- Proxy HTTP history contains at least 30 entries.
- At least 5 entries are in scope.
- At least one request has `Cookie` header.
- At least one request has `Authorization` or custom auth header.

## TC-HH-001 Query first page with defaults
- Tool: `list_proxy_http_history`
- Goal: verify default pagination envelope and default in-scope filtering.
- Request:
```json
{
  "start_id": 0,
  "limit": 5
}
```
- Expected:
1. Response contains `total`, `results`, `next`.
2. `results.length <= 5`.
3. Result set respects `filter.in_scope_only=true` even though `request.in_scope` is omitted from the optimized default shape.
4. If more matching entries exist, `next` is non-null and includes same filter+serialization.

## TC-HH-002 Query with explicit filter and serialization omit policy
- Tool: `list_proxy_http_history`
- Goal: verify structured filter and serialization behavior.
- Request:
```json
{
  "start_id": 0,
  "limit": 10,
  "filter": {
    "in_scope_only": false,
    "methods": ["POST"],
    "status_codes": [200, 401, 403],
    "host_regex": "example|portswigger",
    "has_response": true
  },
  "serialization": {
    "include_binary": false,
    "max_text_body_chars": 1024,
    "text_overflow_mode": "omit",
    "max_binary_body_bytes": 65536
  }
}
```
- Expected:
1. All returned items satisfy filter constraints.
2. Oversized text body appears as `encoding: "omitted"` with `omitted_reason`.
3. Binary body appears omitted when `include_binary=false`.

## TC-HH-003 Query with negative start_id
- Tool: `list_proxy_http_history`
- Goal: verify python-like tail addressing.
- Request:
```json
{
  "start_id": -1,
  "limit": 1,
  "filter": {
    "in_scope_only": false
  }
}
```
- Expected:
1. Returns at most one newest matching record.
2. No validation error for negative `start_id`.

## TC-HH-004 Query with regex validation failure
- Tool: `list_proxy_http_history`
- Goal: verify deterministic invalid regex error.
- Request:
```json
{
  "start_id": 0,
  "limit": 5,
  "filter": {
    "regex": "(abc"
  }
}
```
- Expected:
1. Tool returns `isError=true`.
2. Error text explicitly indicates invalid regex.

## TC-HH-005 Follow-up page using next cursor
- Tool: `list_proxy_http_history`
- Goal: verify no duplicate when following `next`.
- Steps:
1. Execute TC-HH-002.
2. Copy `next` object from response.
3. Execute `list_proxy_http_history` using that exact `next` JSON.
- Expected:
1. Returned page starts at the correct next position.
2. First page last ID is not duplicated unexpectedly in second page.

## TC-HH-006 Get explicit history IDs
- Tool: `get_proxy_http_history_by_ids`
- Goal: verify exact item fetch by Burp IDs.
- Steps:
1. Run TC-HH-001 and collect two IDs from `results`.
2. Request:
```json
{
  "ids": [101, 102],
  "serialization": {
    "text_overflow_mode": "omit"
  }
}
```
(replace IDs with real values)
- Expected:
1. Response has `requested`, `found`, `results`.
2. Each requested ID returns either `item` or `error`.
3. No scope filtering is applied by this tool.

## TC-HH-006A History fields projection
- Tool: `list_proxy_http_history`
- Goal: verify item-level projection without breaking envelope.
- Request:
```json
{
  "start_id": 0,
  "limit": 3,
  "fields": ["id", "time", "request.method", "request.url", "response.status_code"]
}
```
- Expected:
1. Response still contains `total`, `results`, `next`.
2. Each result item contains only `id`, `time`, `request`, `response`.
3. `request` contains only `method` and `url`.
4. `response` contains only `status_code` when a response exists.

## TC-HH-006B History regex excerpts
- Tool: `list_proxy_http_history`
- Goal: verify compact regex triage without full body branches.
- Request:
```json
{
  "start_id": 0,
  "id_direction": "decreasing",
  "limit": 10,
  "filter": {
    "regex": "refresh"
  },
  "fields": ["id", "request.method", "request.url", "response.status_code"],
  "serialization": {
    "regex_excerpt": {
      "context_chars": 48
    }
  }
}
```
- Expected:
1. Response contains `match_context` on each result item.
2. `match_context` contains `excerpts`.
3. `request.body` / `response.body` are absent from the result shape.
4. Matching still works even though the matching branch is not present in final output.

## TC-HH-007 Extract cookie observations
- Tool: `summarize_http_history_cookies`
- Goal: verify aggregation from request/response cookie headers.
- Request:
```json
{
  "limit": 50,
  "offset": 0,
  "order": "desc",
  "in_scope_only": false
}
```
- Expected:
1. Response has `total_entries_scanned`, `unique_cookies`, `observations`.
2. `observations[*]` include `source`, `name`, `value_preview`, `count`.
3. `first_seen_history_id <= last_seen_history_id` for each observation.

## TC-HH-008 Extract auth header observations
- Tool: `summarize_http_history_auth_headers`
- Goal: verify aggregation of auth-like headers.
- Request:
```json
{
  "limit": 50,
  "offset": 0,
  "order": "desc",
  "in_scope_only": false
}
```
- Expected:
1. Response has `total_entries_scanned`, `unique_headers`, `observations`.
2. Headers such as `Authorization`, `X-Api-Key` appear when present.
3. Values are returned as preview, not full long secret blobs.

## TC-HH-009 Edge case: empty history window
- Tool: `summarize_http_history_cookies` and `summarize_http_history_auth_headers`
- Goal: verify stable empty response.
- Request:
```json
{
  "limit": 1,
  "offset": 100000,
  "order": "desc",
  "in_scope_only": false
}
```
- Expected:
1. No crash.
2. Empty `observations`.
3. Counters remain valid numeric values.

## TC-HH-010 Query with decreasing id_direction
- Tool: `list_proxy_http_history`
- Goal: verify reverse walking by stable IDs.
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
