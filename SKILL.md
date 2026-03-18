---
name: Awesome MCP Burp Operations
description: >-
  Use this skill when operating Burp Suite through Awesome MCP for pentest
  workflows: traffic discovery, exact retrieval by IDs/keys, scope control,
  active HTTP execution, Repeater/Intruder/Organizer actions,
  scanner/collaborator flows, and cookie jar operations.
metadata:
  author: vvvvvvvvvvel
  version: "1.1.0"
---

# Awesome MCP Burp Operations

## Use this skill when
- The user wants Burp actions through MCP, not manual Burp UI clicking.
- The task needs stable cursoring over Burp data (`list_*` then `get_*_by_*`).
- The task is pentest/bug bounty oriented and context size matters.

## Prerequisites
- Burp has Awesome MCP extension enabled.
- Endpoint is reachable (default streamable HTTP): `http://127.0.0.1:26001/mcp`.
- Burp has traffic if history/site map tools are used.

## Core operating rules
1. Keep scope as source of truth.
   - Use in-scope queries first (`filter.in_scope_only=true` defaults on list tools).
   - Any scope mutation (`scope_add_*`, `scope_remove_*`) requires explicit user approval.
2. Keep payloads small first, expand only when needed.
   - Discovery calls: minimal serialization.
   - Deep evidence: exact IDs/keys + expanded serialization.
   - For HTTP history / Site Map / Organizer / direct-send results / scanner issues, prefer `fields` or `exclude_fields` before widening serialization.
3. Use list -> narrow -> exact-fetch flow.
   - First `list_*` with filters.
   - Then `get_*_by_ids` / `get_*_by_keys` on selected items.
4. Pagination stop signal is `next == null`.
   - `total` is full source size before filter matching.
5. For fresh session/auth artifacts, traverse from tail.
   - Use `id_direction=decreasing`.

## High-value tool map

### Discovery and retrieval
- `list_proxy_http_history`
- `get_proxy_http_history_by_ids`
- `list_proxy_websocket_history`
- `get_proxy_websocket_messages_by_ids`
- `list_site_map`
- `get_site_map_by_keys`
- `list_organizer_items`
- `get_organizer_items_by_ids`

### Summaries and utilities
- `summarize_http_history_cookies`
- `summarize_http_history_auth_headers`
- `url_encode`, `url_decode`
- `base64_encode`, `base64_decode`
- `generate_random_string`

### Active testing and workflow actions
- `send_http1_requests`
- `send_http2_requests`
- `create_repeater_tabs`
- `send_requests_to_intruder`
- `send_requests_to_intruder_template`
- `send_requests_to_organizer`

### Scope and environment control
- `scope_add_include`, `scope_add_exclude`
- `scope_remove_include`, `scope_remove_exclude`
- `scope_is_url_in_scope`
- `set_proxy_intercept_enabled`, `get_proxy_intercept_enabled`
- `set_task_engine_state`, `get_task_engine_state`
- `get_project_options_json`, `set_project_options_json`
- `get_user_options_json`, `set_user_options_json`
- `list_proxy_request_listeners`
- `get_project_scope_rules`

### Cookies, scanner, collaborator
- `list_cookie_jar`, `set_cookie_jar_cookie`, `expire_cookie_jar_cookie`
- `start_scanner_crawl`, `start_scanner_audit`
- `get_scanner_task_status`, `list_scanner_tasks`, `cancel_scanner_task`
- `list_scanner_issues`, `generate_scanner_report`
- `generate_collaborator_payload`, `list_collaborator_interactions`

## Cursor and pagination model

### HTTP/WebSocket/Organizer list tools
- Cursor fields:
  - `start_id` (inclusive anchor)
  - `id_direction`: `increasing` or `decreasing`
- Defaults:
  - `start_id=0`
  - `id_direction=increasing`
- `start_id < 0` is Python-like index from tail (`-1` last item).
- Always continue with returned `next` object as-is.
- `next == null` is the only reliable stop condition for current query state.
- `total` is source size before filter matching (not matched-count).
- Prefer page size in the `20..100` range for stable agent loops.

### Site Map list tool
- Cursor field: `start_after_key` (exclusive).
- For restart-safe continuation, pass the previous response `next`.
- If `start_after_key not found`, restart with `start_after_key=null` and de-duplicate by `results[].key`.

### Scanner/Cookie list tools
- Offset model (`offset`, `limit`, `has_more`) instead of cursor `next`.
- `list_cookie_jar.order` accepts only `asc` or `desc`.

## Filter model

### HTTP history filter fields
- `in_scope_only`
- `regex`
- `methods[]`
- `host_regex`
- `mime_types[]`
- `inferred_mime_types[]`
- `status_codes[]`
- `listener_ports[]`
- `has_response`
- `time_from`, `time_to`

### SiteMap/Organizer filter fields
- `in_scope_only`
- `regex`
- `methods[]`
- `host_regex`
- `mime_types[]`
- `inferred_mime_types[]`
- `status_codes[]`
- `has_response`
- `time_from`, `time_to`

### WebSocket filter fields
- `in_scope_only`
- `regex`
- `direction` (`client_to_server` | `server_to_client`)
- `web_socket_ids[]`
- `host_regex`
- `listener_ports[]`
- `has_edited_payload`
- `time_from`, `time_to`

### Filter correctness notes
- Array fields are schema-defined arrays (`methods: ["POST"]`).
- Some agent clients are normalized from scalar -> array at runtime, but do not rely on that behavior when constructing calls deliberately.
- Booleans must be booleans (`true/false`, not strings).
- Use `has_response=true` when response-dependent analysis is needed.
- Regex escaping depends on client layer; avoid double-escaping at runtime.

## Serialization strategy

Serialization options shape output size; they do not filter dataset membership.
- `serialization.regex_excerpt` is the compact regex-triage mode for supported HTTP tools.
- `serialization.regex_excerpt` object:
  - `context_chars` controls left/right snippet width around each match.
  - `regex` is the excerpt pattern.
- `serialization.regex_excerpt` must be an object, not a bare regex string.
- `match_context` is available when `serialization.regex_excerpt` is enabled.
- `serialization.regex_excerpt` is supported on:
  - `list_proxy_http_history`
  - `get_proxy_http_history_by_ids`
  - `list_site_map`
  - `get_site_map_by_keys`
  - `list_organizer_items`
  - `send_http1_requests`
  - `send_http2_requests`
- When `serialization.regex_excerpt` is enabled:
  - do not request `request.body`, `response.body`, `request.raw`, or `response.raw` in `fields`
  - if `fields` is `null`, the optimized default shape is returned without body/raw branches

## Item projection strategy

For HTTP history, Site Map, and Organizer list/get tools:
- `fields`: include only listed item paths
- `exclude_fields`: drop listed item paths from otherwise optimized default items
- only one of `fields` or `exclude_fields` may be non-null
- if both are null, the optimized default item shape is returned
- projection applies to result items only, not to envelope keys such as `total`, `next`, `requested`, `found`, or `results`
- filtering and regex matching happen before projection on source Burp data
- a record may be returned because it matched on a branch that is not present in final output
- if a projected path or subtree does not exist on a given item, it is skipped silently; it is not a tool error
- this applies to any missing branch, not only common optional ones such as `response.*` or body/header fields
- if `match_context` is present, it can be projected like any other optional branch
- optimized default HTTP output omits:
  - `listener_port`, `edited`
  - root `in_scope`
  - `request.path`, `request.query`, `request.in_scope`
  - empty `response.cookies`
  - `response.stated_mime_type` / `response.inferred_mime_type` when they equal `response.mime_type`
- request those branches explicitly through `fields` if they matter for the current step

### HTTP history item paths

Top-level:
- `id`
- `time`
- `listener_port`
- `edited`
- `notes`
- `request`
- `response`

`request` subtree:
- `request.method`
- `request.url`
- `request.path`
- `request.query`
- `request.host`
- `request.port`
- `request.secure`
- `request.in_scope`
- `request.http_version`
- `request.headers`
- `request.body`
- `request.raw`

Availability notes:
- with `fields`, `request.headers` is materialized only when the selected paths require it
- with `exclude_fields` or with both projection fields null, `request.headers` is part of the optimized default non-raw shape
- with `fields`, `request.body` is materialized only when the selected paths require it
- with `exclude_fields` or with both projection fields null, `request.body` is part of the optimized default non-raw shape
- `request.raw` is populated only when `fields` explicitly includes `request.raw`

`response` subtree:
- `response.status_code`
- `response.reason_phrase`
- `response.http_version`
- `response.mime_type`
- `response.stated_mime_type`
- `response.inferred_mime_type`
- `response.headers`
- `response.cookies`
- `response.body`
- `response.raw`

Availability notes:
- with `fields`, `response.headers` / `response.cookies` are materialized only when the selected paths require them
- with `exclude_fields` or with both projection fields null, `response.headers` are part of the optimized default non-raw shape
- empty `response.cookies` are omitted from the optimized default shape
- with `fields`, `response.body` is materialized only when the selected paths require it
- with `exclude_fields` or with both projection fields null, `response.body` is part of the optimized default non-raw shape
- `response.raw` is populated only when `fields` explicitly includes `response.raw`
- when `response.stated_mime_type` and `response.inferred_mime_type` equal `response.mime_type`, only `response.mime_type` stays in the optimized default shape

Use full roots only when the whole subtree is needed.
- `fields: ["request"]` returns the whole serialized request subtree.
- if `serialization.regex_excerpt` is enabled, `fields: ["request"]` still excludes `request.body` / `request.raw`.
- `exclude_fields: ["response.body"]` removes only the body subtree.
- Prefer leaf paths such as `request.method` or `response.status_code` for low-token discovery.

### Site Map item paths

Top-level:
- `key`
- `url`
- `in_scope`
- `notes`
- `request`
- `response`

`request.*` and `response.*` use the same subtree names as HTTP history.

### Organizer item paths

Top-level:
- `id`
- `status`
- `url`
- `in_scope`
- `notes`
- `request`
- `response`

`request.*` and `response.*` use the same subtree names as HTTP history.

### Direct-send result paths

For `send_http1_requests` / `send_http2_requests`, projection paths are relative to each successful `results[].result` object.

Top-level:
- `status_code`
- `has_response`
- `request`
- `response`

`request.*` and `response.*` use the same subtree names as HTTP history.

### Scanner issue item paths

Top-level:
- `name`
- `severity`
- `confidence`
- `base_url`
- `detail`
- `remediation`
- `issue_background`
- `remediation_background`
- `typical_severity`
- `type_index`
- `request_responses`

`request_responses` subtree:
- `request_responses` is plural because a Burp scanner issue can carry multiple attached request/response pairs
- `request_responses.request`
- `request_responses.request.method`
- `request_responses.request.url`
- `request_responses.request.path`
- `request_responses.request.query`
- `request_responses.request.host`
- `request_responses.request.port`
- `request_responses.request.secure`
- `request_responses.request.in_scope`
- `request_responses.request.http_version`
- `request_responses.request.headers`
- `request_responses.request.body`
- `request_responses.request.raw`
- `request_responses.response`
- `request_responses.response.status_code`
- `request_responses.response.reason_phrase`
- `request_responses.response.http_version`
- `request_responses.response.mime_type`
- `request_responses.response.stated_mime_type`
- `request_responses.response.inferred_mime_type`
- `request_responses.response.headers`
- `request_responses.response.cookies`
- `request_responses.response.body`
- `request_responses.response.raw`

### Practical rules
- Use `fields` when you know exactly what you need.
- Use `exclude_fields` when you want the normal full shape minus a few heavy branches.
- Prefer:
  - `fields` for broad discovery
  - `exclude_fields` for medium-detail review
  - full shape only for exact evidence pulls

Example include projection:
```json
{
  "fields": ["id", "time", "request.method", "request.url", "response.status_code"]
}
```

Example exclude projection:
```json
{
  "exclude_fields": ["request.headers", "response.headers", "response.body", "response.raw"]
}
```

Example with missing optional branches:
```json
{
  "fields": ["id", "request.headers", "response.body"]
}
```

Behavior:
- any missing path or subtree is simply absent in output
- this includes optional branches like `response.*`, omitted serialization branches, or any other non-present nested path
- the tool still succeeds

### Minimal discovery profile
```json
{
  "max_text_body_chars": 512,
  "text_overflow_mode": "omit"
}
```

### Standard inspection profile
```json
{
  "include_binary": false,
  "max_text_body_chars": 4096,
  "text_overflow_mode": "omit"
}
```

### Deep evidence profile
```json
{
  "include_binary": true,
  "max_text_body_chars": 20000,
  "max_binary_body_bytes": 10485760,
  "text_overflow_mode": "truncate"
}
```

## Operational workflows

### A) Recent auth/session hunting
1. `list_proxy_http_history` with `id_direction=decreasing`, strict `host_regex`, minimal serialization.
2. `summarize_http_history_auth_headers` + `summarize_http_history_cookies`.
3. `get_proxy_http_history_by_ids` on candidate IDs with standard/deep profile.

### B) Replay from observed traffic
1. Discover via `list_proxy_http_history`.
2. Pull exact requests by ID.
3. Send to `create_repeater_tabs` or `send_requests_to_intruder*`.

### C) Scope-aware recon
1. Validate scope with `scope_is_url_in_scope`.
2. If user approved, adjust scope via `scope_add_*`/`scope_remove_*`.
3. Use `list_site_map` for surface, `list_proxy_http_history` for concrete events.

### D) Scanner triage (Pro)
1. Start tasks (`start_scanner_crawl` / `start_scanner_audit`).
2. Track via `list_scanner_tasks` and `get_scanner_task_status`.
3. Triage with `list_scanner_issues`.
4. Export via `generate_scanner_report`.

### E) Multi-client traffic separation for auth/IDOR/BAC testing
1. If the user can help with setup, ask them to run different browsers/devices/roles through different Burp Proxy listeners.
2. Keep the separation simple:
   - browser A / low-privilege user -> listener port A
   - browser B / admin or second user -> listener port B
3. Choose the query mode based on what you need:
   - use `filter.listener_ports` when only one listener flow is needed server-side
   - omit that filter when you want one mixed window and will compare flows yourself
4. Split or compare mixed result sets client-side using `results[].listener_port`.
5. Use this when hunting IDOR, broken access control, session confusion, role drift, or cross-account state mixups.

## Important tool semantics
- `list_site_map` gives structured/deduplicated surface; `list_proxy_http_history` gives chronological concrete traffic.
- HTTP history entries include `listener_port`, so different Burp Proxy listeners can be used as a practical separation signal between browsers, roles, or devices.
- `filter.listener_ports` exists for `list_proxy_http_history` and `list_proxy_websocket_history`; it is not part of Site Map / Organizer filters.
- `send_http1_requests` and `send_http2_requests` are direct sends; they do not automatically appear in Proxy history.
- If later discovery via `list_proxy_http_history` / `list_site_map` is required, route traffic through a Burp Proxy listener first.
- `send_http1_requests` and `send_http2_requests` also support `fields` / `exclude_fields`; projection paths are relative to each successful `results[].result` object (`status_code`, `has_response`, `request.*`, `response.*`).
- `send_http2_requests` should use HTTP/2 mode options and `headers_list` when duplicate header names must be preserved.
- `get_site_map_by_keys` accepts only keys from `list_site_map.results[].key`.
- `list_scanner_tasks` is runtime-local to Awesome MCP tracking; it is not a global historical scanner inventory.
- `list_scanner_issues` stays light by default: `detail`, `remediation`, definition fields, and `request_responses` are absent unless explicitly requested through `fields`.
- When `fields` includes `request_responses`, nested HTTP branches follow the same auto-materialization rules as history/site-map/organizer.
- Use `max_request_responses` to cap attached request/response pairs.
- `list_scanner_issues` uses offset pagination; while scans are changing, page boundaries can shift.
- `expire_cookie_jar_cookie` is expire semantics (practical delete), not hard delete.
- `list_cookie_jar` is not cursor-based; use `offset` / `limit`, and keep `order` to `asc` or `desc` exactly.

## Recommended call patterns

### Pattern 1: last 20 in-scope with responses
```json
{
  "start_id": 0,
  "id_direction": "decreasing",
  "limit": 20,
  "filter": {
    "in_scope_only": true,
    "has_response": true
  },
  "serialization": {
    "max_text_body_chars": 512,
    "text_overflow_mode": "omit"
  }
}
```

This is the cheapest useful default:
- stable envelope + default optimized item shape
- no duplicated request path/query fields
- no empty cookie arrays
- no duplicate stated/inferred MIME fields when they add no signal

### Pattern 2: continue page
- Reuse response `next` directly.

### Pattern 3: deep pull by IDs
```json
{
  "ids": [123, 140, 155],
  "fields": ["id", "request.raw", "response.raw"]
}
```

### Pattern 4: compact regex triage without full bodies
```json
{
  "start_id": 0,
  "id_direction": "decreasing",
  "limit": 20,
  "filter": {
    "in_scope_only": true,
    "regex": "refresh_token"
  },
  "fields": ["id", "time", "request.method", "request.url", "response.status_code"],
  "serialization": {
    "regex_excerpt": {
      "context_chars": 10
    }
  }
}
```

### Pattern 5: split filter and excerpt regex
```json
{
  "start_id": 0,
  "limit": 20,
  "filter": {
    "regex": "auth-refresh"
  },
  "fields": ["id", "request.url", "response.status_code"],
  "serialization": {
    "regex_excerpt": {
      "regex": "refresh_token=[A-Za-z0-9._-]+",
      "context_chars": 64
    }
  }
}
```

### Pattern 6: explicit compact discovery before exact follow-up
```json
{
  "start_id": 0,
  "id_direction": "decreasing",
  "limit": 30,
  "filter": {
    "in_scope_only": true,
    "methods": ["POST"]
  },
  "fields": ["id", "time", "request.method", "request.url", "response.status_code"]
}
```

Then follow with:

```json
{
  "ids": [123, 124],
  "fields": [
    "id",
    "request.method",
    "request.url",
    "request.headers",
    "request.body",
    "response.status_code",
    "response.headers",
    "response.body"
  ],
  "serialization": {
    "include_binary": false,
    "max_text_body_chars": 4000,
    "text_overflow_mode": "omit"
  }
}
```

### Pattern 7: listener-separated traffic review
```json
{
  "start_id": 0,
  "id_direction": "decreasing",
  "limit": 20,
  "filter": {
    "in_scope_only": true,
    "listener_ports": [8082]
  },
  "fields": ["id", "time", "listener_port", "request.method", "request.url", "response.status_code"]
}
```

## Anti-patterns
- Pulling full raw/binary bodies on broad list queries.
- Skipping discovery and calling `get_*_by_*` blindly.
- Using regex-only filtering when structured fields are available.
- Editing project/user options without round-tripping current JSON first.

## Troubleshooting
- `Expected JsonArray`: send arrays for array fields (`methods`, `severity`, `confidence`, etc.).
- Empty result set: relax one filter dimension at a time.
- Regex mismatch: check escaping at the client layer.
- Scanner/collaborator issues: verify Burp edition and runtime conditions.
