---
name: Awesome MCP Burp Operations
description: Use this skill when the user asks to operate Burp Suite through Awesome MCP, including traffic querying, scope control, active HTTP testing, Repeater/Intruder/Organizer workflows, scanner orchestration, collaborator checks, and cookie jar operations.
metadata:
  author: vvvvvvvvvvel
  version: "1.0.1"
---

# Awesome MCP Burp Operations

## Purpose
Operate Burp Suite through Awesome MCP with agent-friendly, low-noise workflows that are safe for large projects and effective for pentest/bug bounty tasks.

## Inputs / Prerequisites
- Burp Suite with Awesome MCP extension loaded and running.
- MCP endpoint available (default: `http://127.0.0.1:26001/mcp`).
- Transport: `streamable_http` preferred.
- Traffic exists in Burp (Proxy history / Site Map) when using read/query tools.

## Tool Families

### History and retrieval
- `list_proxy_http_history`
- `get_proxy_http_history_by_ids`
- `list_proxy_websocket_history`
- `get_proxy_websocket_messages_by_ids`
- `list_site_map`
- `get_site_map_by_keys`
- `summarize_http_history_cookies`
- `summarize_http_history_auth_headers`

### Active HTTP and workflow
- `send_http1_requests`
- `send_http2_requests`
- `create_repeater_tabs`
- `send_requests_to_intruder`
- `send_requests_to_intruder_template`
- `send_requests_to_organizer`
- `list_organizer_items`
- `get_organizer_items_by_ids`

### Scope and utility
- `scope_add_include`
- `scope_add_exclude`
- `scope_remove_include`
- `scope_remove_exclude`
- `scope_is_url_in_scope`
- `url_encode`
- `url_decode`
- `base64_encode`
- `base64_decode`
- `generate_random_string`

### Config/runtime
- `get_project_options_json`
- `get_user_options_json`
- `list_proxy_request_listeners`
- `get_project_scope_rules`
- `set_project_options_json`
- `set_user_options_json`
- `set_task_engine_state`
- `get_task_engine_state`
- `set_proxy_intercept_enabled`
- `get_proxy_intercept_enabled`
- `get_active_text_editor_contents`
- `set_active_text_editor_contents`

### Scanner/Collaborator/Cookies
- `start_scanner_crawl`
- `start_scanner_audit`
- `get_scanner_task_status`
- `list_scanner_tasks`
- `cancel_scanner_task`
- `generate_scanner_report`
- `list_scanner_issues`
- `generate_collaborator_payload`
- `list_collaborator_interactions`
- `list_cookie_jar`
- `set_cookie_jar_cookie`
- `expire_cookie_jar_cookie`

## Core Operating Rules

1. Keep Burp scope as the source of truth.
   - Use `filter.in_scope_only=true` by default (history/site map defaults).
   - Any scope modification should be user-approved before calling `scope_add_*` / `scope_remove_*`.
   - Use `in_scope_only=false` only when explicitly requested or when a scoped approach is intentionally not possible.
2. Keep context small by default.
   - Start with minimal serialization for discovery.
   - Expand bodies/raw only when needed.
3. Use cursor flow, not random jumps.
   - Always follow `next` returned by list tools.
   - `next=null` means end of data for current filter.
4. Use exact-fetch tools after discovery.
   - Query with filters first, then pull details by ID/key only for interesting entries.
5. Prefer tail-first for “fresh” artifacts.
   - Session tokens, auth headers, and recent failures are usually near the end.

## Cursor Strategy (Critical)

### HTTP and WebSocket list tools
- Cursor fields:
  - `start_id` (inclusive)
  - `id_direction`: `increasing` or `decreasing`
- Default:
  - `start_id=0`
  - `id_direction=increasing`

### Practical patterns
1. Full historical sweep (for broad recon):
   - `start_id=0`, `id_direction=increasing`, moderate `limit`.
2. Latest-first triage (for current session behavior):
   - `start_id=0`, `id_direction=decreasing`.
3. Session/token hunting:
   - start from tail (`decreasing`) with strict host/method/regex filters.
4. Continue pagination:
   - re-use response `next` object directly.
   - do not hand-craft the next cursor unless required.

### Pagination semantics
- `total` is the source list size before filter matching.
- `next` is the authoritative continuation signal for the current filter/cursor state.
- `next=null` means there are no more matched entries for that query state.
- Do not interpret `total` as matched count.
- For HTTP/WebSocket list tools, `start_id` matches Burp item IDs shown in Burp UI/history views.
- `start_id` is inclusive.
- Negative `start_id` is index-style (Python-like): `-1` means the last entry, `-2` means the previous one.

### Limits and clamping
- For list-style history queries, server-side `limit` is clamped to `1..500`.
- Use moderate page sizes (`20..100`) and continue with `next` for stable agent loops.

## Filter Strategy

### HTTP filter fields
- `in_scope_only: boolean`
- `regex: string`
- `methods: string[]`
- `host_regex: string`
- `mime_types: string[]`
- `inferred_mime_types: string[]`
- `status_codes: int[]`
- `has_response: boolean`
- `time_from: ISO-8601`
- `time_to: ISO-8601`

### WebSocket filter fields
- `in_scope_only: boolean`
- `regex: string`
- `direction: ["client_to_server" | "server_to_client"]`
- `web_socket_ids: int[]`
- `host_regex: string`
- `listener_ports: int[]`
- `has_edited_payload: boolean`
- `time_from`, `time_to`

### Filter tips
- Regex escaping depends on how input is sent:
  - If you send raw JSON text, use `\\.` in JSON so runtime receives `\.`.
  - If client UI already unescapes for you, enter `\.` directly.
  - Do not double-escape into runtime `\\.` unless you want a literal backslash.
- `methods` must be an array (`["POST"]`), not a scalar.
- Booleans must be JSON booleans (`true`/`false`), not strings (`"true"`/`"false"`).
- Use `has_response=true` to avoid half-open history entries when response-dependent analysis is needed.

## Serialization Strategy

### Why it matters
Serialization options control output volume, not filtering semantics.

### HTTP defaults
- `include_headers=true`
- `include_request_body=true`
- `include_response_body=true`
- `include_raw_request=false`
- `include_raw_response=false`
- `include_binary=false`
- `max_text_body_chars=1024`
- `max_binary_body_bytes=65536`
- `text_overflow_mode="omit"`

### Minimal discovery profile
Use this for first-pass querying:

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
Use this for most practical pentest analysis after discovery:

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

### Deep forensics profile
Use only for narrowed IDs/keys when binary/raw evidence is explicitly needed:

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

## High-Value Workflows

### 1) Find recent auth/session artifacts
1. Call `list_proxy_http_history` with:
   - `id_direction=decreasing`
   - start without a method filter (or include `GET` when narrowing is needed)
   - `filter.host_regex` set to target
   - lightweight serialization
   - apply strict method filters only after first candidate artifacts are identified
2. Call `summarize_http_history_auth_headers` and `summarize_http_history_cookies`.
3. Pull exact entries with `get_proxy_http_history_by_ids` for top candidates.

### 2) Build test requests from observed traffic
1. Discover interesting entries via `list_proxy_http_history`.
2. Fetch full raw requests by ID.
3. Push to `create_repeater_tabs` for manual exploitation.
4. Push selected requests to `send_requests_to_intruder(_template)` for automation.

### 3) Scope-aware recon loop
1. Validate/adjust scope: `scope_add_include`, `scope_add_exclude`, `scope_is_url_in_scope`.
2. Run `list_site_map` for target structure and known endpoints.
3. Use `list_proxy_http_history` for live behavior and parameter-rich requests.

### 4) Scanner orchestration (Pro)
1. Start with `start_scanner_crawl` or `start_scanner_audit`.
2. Track with `list_scanner_tasks` and `get_scanner_task_status`.
3. Triage with `list_scanner_issues`.
4. Export with `generate_scanner_report`.

## Tool-Specific Notes

### `list_site_map` vs `list_proxy_http_history`
- `list_site_map`: deduplicated/structured application surface.
- `list_proxy_http_history`: chronological concrete traffic events.
- Use both: site map for coverage, history for exact replay/exploitation.

### `get_site_map_by_keys`
- Keys must come from `list_site_map.results[].key`.
- This is exact lookup, not search.

### Site Map cursor recovery
- `start_after_key` is exclusive and must refer to an existing key.
- If you get `start_after_key not found`, restart with `start_after_key=null` using the same filter.
- De-duplicate client-side by `results[].key` while resuming.

### `send_http1_requests`
- Best for raw HTTP/1.1 request replay and parser-edge testing.
- Use when you want explicit wire-like request text control.
- Requests sent by this tool do not appear in Proxy HTTP history.

### `send_http2_requests`
- `request_options.http_mode` must be `http_2` / `http_2_ignore_alpn` or omitted.
- Use `headers_list` when duplicate header names must be preserved.
- Requests sent by this tool do not appear in Proxy HTTP history.

### Choosing `send_http1_requests` vs `send_http2_requests`
- Use HTTP/1.1 tool for classic replay/smuggling-oriented request crafting.
- Use HTTP/2 tool for true H2 behavior and pseudo-header aware testing.
- Keep both tools; they are protocol-specific and not interchangeable in real cases.
- Agent decision rule:
  - If you only need immediate request/response output, use `send_http*_requests`.
  - If you need this traffic to be discoverable later via `list_proxy_http_history`/`list_site_map`, you must route the request through a Burp Proxy listener first, and only then query history.

### `list_scanner_issues` serialization note
- Default scanner issue output is body-light:
  - `include_request_body=false`
  - `include_response_body=false`
- For deep issue evidence, enable `include_request_response=true` and pass explicit serialization options.

### `list_scanner_issues` pagination caveat
- This tool uses `offset` pagination.
- While scans are running and issues are changing, offset pages can shift.
- For deterministic triage, prefer running it on a stable issue set (or re-query from offset `0` after scan state changes).

### `list_scanner_tasks` limitation
- Returns only tasks created and tracked by the current Awesome MCP runtime.
- After Burp/extension restart, this view is not a complete historical inventory of Burp scanner tasks.

### `generate_collaborator_payload` / `list_collaborator_interactions`
- Generate payload first, then trigger it externally, then poll interactions.
- Keep `payload_id` and `secret_key` from generation result.

### Cookie deletion semantics
- `expire_cookie_jar_cookie` expires matching cookies (practical delete behavior in Burp context).

## Recommended Call Patterns

### Pattern A: Most recent 20 in-scope entries
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

### Pattern B: Continue page
Use response `next` as-is.

### Pattern C: Pull exact entries deeply
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

## Anti-Patterns
- Do not fetch large bodies/raw for broad list queries.
- Do not use `get_*_by_ids` before discovery phase.
- Do not rely only on regex when structured filters can reduce noise faster.
- Do not edit full project/user options without round-tripping current JSON first.

## Troubleshooting
- Error `Expected JsonArray`: send arrays for list fields (`methods`, `severity`, etc.).
- Empty results with strict filters: disable one filter dimension at a time.
- Regex mismatch: verify escaping in JSON strings.
- Missing scanner/collaborator behavior: ensure Burp edition/features and runtime conditions are valid.
