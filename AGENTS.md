# Awesome MCP Repository Agent Rules

## Purpose

- Use this file as the always-on base context when working in this repository.
- Keep it short, stable, and focused on repository-wide defaults.
- Put long workflows, examples, and operational playbooks in skills, not here.

## Primary Tooling Default

- When a task involves Burp operations through this repository, prefer Awesome MCP over manual Burp UI work.
- Load the `Awesome MCP Burp Operations` skill for operational patterns, examples, and tool-specific workflows.

## Repository Validation Defaults

- For Kotlin or contract changes, run `./gradlew test`.
- If a change touches schemas, pagination, history/query services, serialization, transport, or settings persistence, also run `./gradlew integrationTest`.
- If a change touches Kotlin sources, run `./gradlew ktlintCheck` or `./gradlew lintAndFormat`.
- Live Burp verification is opt-in. Use `./gradlew liveBurpTest` only when the task truly needs a running Burp instance to verify transport, scope, or UI-to-server behavior.

## Wire Contract Discipline

- Preserve `snake_case` tool names and wire keys.
- Preserve stable response envelopes for list/get tools and do not change them casually.
- If schema shape, defaults, pagination, serialization, or endpoint semantics change, update tests, `README.md`, `SKILL.md`, and relevant `tests-cases/*.md` in the same task.

## Settings UX Invariants

- `Enabled` starts or stops the server immediately and is independent from `Apply`.
- `Apply` commits endpoint settings such as host, port, transport, and storage scope.
- The displayed endpoint URL reflects applied settings, not unsaved edits.

## Core Awesome MCP Defaults

- Treat Burp scope as the source of truth for testing boundaries.
- Any scope mutation through MCP requires explicit user approval.
- Default MCP endpoint is `http://127.0.0.1:26001/mcp` unless the user configures otherwise.
- Prefer streamable HTTP unless the user explicitly needs SSE.

## Retrieval Model

- Prefer `list_*` tools for discovery and `get_*_by_ids` / `get_*_by_keys` for exact follow-up.
- Do not pull heavy request/response bodies during broad discovery unless the task clearly needs them.
- Serialization options change output size and shape only; they do not affect which records match.
- `serialization.regex_excerpt` is the compact regex-triage mode for supported HTTP tools.
- When `serialization.regex_excerpt` is enabled:
  - `match_context` may be requested or excluded through normal `fields` / `exclude_fields` projection
  - `request.body`, `response.body`, `request.raw`, and `response.raw` must not be requested in `fields`
- HTTP list/get tools also support item-level projection:
  - `fields`: include only listed item paths
  - `exclude_fields`: remove listed item paths from otherwise optimized default items
- Projection applies to result items only, not to stable envelopes such as `total`, `next`, `requested`, `found`, or `results`.
- Only one of `fields` or `exclude_fields` may be non-null; if both are null, the optimized default item shape is returned.
- For HTTP history, Site Map, Organizer, direct-send results, and scanner request/response snapshots, headers and request/response bodies are materialized automatically from `fields` / `exclude_fields`.
- `request.raw` and `response.raw` are materialized only when explicitly requested in `fields`.
- The optimized default HTTP shape omits:
  - `listener_port`, `edited`
  - root-level `in_scope`
  - `request.path`, `request.query`, `request.in_scope`
  - empty `response.cookies`
  - `response.stated_mime_type` / `response.inferred_mime_type` when they equal `response.mime_type`
- Filtering and regex matching happen on source Burp data before projection; a record may match a filter because of a branch that is omitted from final output.

## Cursor and Pagination Invariants

- For HTTP history, WebSocket history, and Organizer, pagination is cursor-like:
  - `start_id` is an inclusive anchor.
  - `id_direction` values are `increasing` or `decreasing`.
  - continue with the returned `next` object when present.
- `next == null` is the reliable stop condition.
- `total` is the full source size before filter matching, not the matched-count.
- Negative `start_id` values are Python-like anchors from the tail (`-1` means the last item).

## Site Map Invariants

- `list_site_map` uses `start_after_key`, not `start_id`.
- `start_after_key` is exclusive.
- `get_site_map_by_keys` accepts only keys returned by `list_site_map.results[].key`.

## History and Filter Defaults

- `filter.in_scope_only=true` is the safe default for list tools unless the user explicitly wants broader traffic.
- Use `listener_port` / `listener_ports` as a practical separation signal when the user routes different browsers, devices, or roles through different Burp proxy listeners.
- Regex behavior depends on the client layer; avoid assuming double-escaping rules without checking the actual sent JSON.

## Active Send Semantics

- `send_http1_requests` and `send_http2_requests` are direct sends to targets.
- Requests from those tools do not automatically appear in Proxy HTTP history.
- If later discovery through `list_proxy_http_history` or `list_site_map` is required, traffic must be sent through a Burp Proxy listener first.

## Input Contract Notes

- Follow the JSON schema exactly when possible.
- Some inputs are normalized leniently at runtime, but do not rely on that behavior deliberately when constructing new calls.
- Prefer schema-valid arrays, booleans, and integers over scalar shortcuts or stringified values.
- When a tool exposes a strict enum, use the documented values exactly. Example: `list_cookie_jar.order` supports `asc` or `desc`.

## Documentation Split

- Keep this file focused on defaults and invariants.
- Keep the Awesome MCP skill focused on operational workflows, examples, and pentest-oriented usage guidance.
