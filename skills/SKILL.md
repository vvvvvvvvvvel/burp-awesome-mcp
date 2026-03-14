---
name: Awesome MCP Burp Operations
description: >-
  Use this skill when operating Burp Suite through Awesome MCP for pentest
  workflows: traffic discovery, exact retrieval by IDs/keys, scope control,
  active HTTP execution, Repeater/Intruder/Organizer actions,
  scanner/collaborator flows, and cookie jar operations.
metadata:
  author: vvvvvvvvvvel
  version: "1.0.2"
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

### Minimal discovery profile
```json
{
  "include_headers": false,
  "include_request_body": false,
  "include_response_body": false,
  "include_raw_request": false,
  "include_raw_response": false
}
```

### Standard inspection profile
```json
{
  "include_headers": true,
  "include_request_body": true,
  "include_response_body": true,
  "include_raw_request": false,
  "include_raw_response": false,
  "include_binary": false,
  "max_text_body_chars": 4096,
  "text_overflow_mode": "omit"
}
```

### Deep evidence profile
```json
{
  "include_headers": true,
  "include_request_body": true,
  "include_response_body": true,
  "include_raw_request": true,
  "include_raw_response": true,
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
- `send_http2_requests` should use HTTP/2 mode options and `headers_list` when duplicate header names must be preserved.
- `get_site_map_by_keys` accepts only keys from `list_site_map.results[].key`.
- `list_scanner_tasks` is runtime-local to Awesome MCP tracking; it is not a global historical scanner inventory.
- `list_scanner_issues` defaults are body-light (`include_request_body=false`, `include_response_body=false`); enable request/response detail only for narrowed issue sets.
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
    "include_headers": false,
    "include_request_body": false,
    "include_response_body": false
  }
}
```

### Pattern 2: continue page
- Reuse response `next` directly.

### Pattern 3: deep pull by IDs
```json
{
  "ids": [123, 140, 155],
  "serialization": {
    "include_headers": true,
    "include_request_body": true,
    "include_response_body": true,
    "include_raw_request": true,
    "include_raw_response": true
  }
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
