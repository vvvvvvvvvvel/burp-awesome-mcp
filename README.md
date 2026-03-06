# Awesome MCP

![MCP Pentest Command Desk](assets/images/mcp-pentest-command-desk.png)

Awesome MCP is a Burp Suite extension that runs an MCP server directly inside Burp and exposes agent-first tools for pentest workflows.

This project is intentionally optimized for `operator + agent` usage:
- stable object addressing from Burp UI
- strict schemas with snake_case contracts
- pagination-safe history queries
- context-safe request/response serialization
- practical active tools (HTTP send, Repeater, Intruder, scope, cookie jar)

## Why this exists

Typical Burp MCP bridges make agents do expensive loops and return noisy payloads. Awesome MCP fixes this with:
- stable IDs and direct fetch APIs (`list_*` + `get_*_by_ids` / `get_*_by_keys`)
- predictable envelopes (`total`, `next`, `results`)
- explicit body controls (binary include, size limits, oversized text mode)
- deterministic cursor paging over full Burp lists

## Core architecture

Main modules:
- `ExtensionBase` — extension lifecycle, tab registration, settings wiring
- `AgentMcpServerManager` — in-process Ktor + MCP transport server
- `McpSettingsStore` — settings persistence (global/project, user-selectable)
- `ToolRegistrar` — MCP tool catalog + handlers
- `HistoryQueryService` — Proxy/SiteMap/WebSocket query + get-by-id services
- `Serialization` — safe payload shaping for HTTP/WebSocket content
- `McpOutputLogger` — compact MCP activity logging to Burp Output + Event log

## Transport

Supported transports:
- `sse`
- `streamable_http`

Default runtime values:
- host: `127.0.0.1`
- port: `26001`
- transport: `streamable_http`
- enabled: `true`

Endpoint rules:
- SSE: `http://<host>:<port>`
- Streamable HTTP: `http://<host>:<port>/mcp`

If host is wildcard (`0.0.0.0`/`::`), copy endpoint uses loopback for client convenience.

## Settings UX model

In Awesome MCP tab:
- `Enabled` toggle starts/stops server immediately (independent from Apply)
- `Apply` applies endpoint settings (`host`, `port`, `transport`, storage scope checkbox)
- endpoint URL reflects only applied settings
- Apply button is enabled only when settings differ from applied snapshot

Persistence scope:
- checkbox: `Store settings in current project`
- unchecked: save in Burp global preferences
- checked: save in current project extension data
- scope checkbox itself is persisted in project data

## Tool catalog

### History and retrieval
- `list_proxy_http_history`
- `get_proxy_http_history_by_ids`
- `list_proxy_websocket_history`
- `get_proxy_websocket_messages_by_ids`
- `list_site_map`
- `get_site_map_by_keys`
- `summarize_http_history_cookies`
- `summarize_http_history_auth_headers`

### Active HTTP
- `send_http1_requests` (bulk)
- `send_http2_requests` (bulk)

HTTP transport notes:
- `send_http1_requests` forwards raw request content as provided (no newline/body normalization).
- `send_http2_requests` enforces HTTP/2 mode:
  `request_options.http_mode` must be `http_2` or `http_2_ignore_alpn` (or omitted).
- for duplicate HTTP/2 header names, use `items[].headers_list` (`[{name,value}, ...]`).

### Burp workflow integration
- `create_repeater_tabs` (bulk)
- `send_requests_to_intruder` (bulk)
- `send_requests_to_intruder_template` (bulk)
- `send_requests_to_organizer` (bulk)
- `list_organizer_items`
- `get_organizer_items_by_ids`

### Scope control
- `scope_add_include`
- `scope_add_exclude`
- `scope_remove_include`
- `scope_remove_exclude`
- `scope_is_url_in_scope`

`scope_add_include` / `scope_add_exclude` support:
- `include_subdomains` (optional, default false)
- when true, Awesome MCP updates project scope host rule (`target.scope.include/exclude`) with `include_subdomains=true`
- `url` accepts absolute URL, short host form (`host`, `host:port`), or lightweight prefix (`a/`, `1`)
- validation is intentionally permissive; only obvious junk without letters/digits is rejected
- input value is preserved as provided; normalization to absolute URL is used only as internal fallback when needed

`scope_remove_include` / `scope_remove_exclude` support:
- remove matching rules from `target.scope.include/exclude`
- optional `include_subdomains` selector to remove only one variant when both rules exist for the same prefix

### Utilities
- `url_encode` (bulk)
- `url_decode` (bulk)
- `base64_encode` (bulk)
- `base64_decode` (bulk)
- `generate_random_string`
- `list_cookie_jar`
- `set_cookie_jar_cookie`
- `expire_cookie_jar_cookie`

Cookie delete note:
- `expire_cookie_jar_cookie` expires matching cookies (Montoya Cookie Jar has no hard-delete API).

### Burp control and config
- `set_proxy_intercept_enabled`
- `get_proxy_intercept_enabled`
- `set_task_engine_state`
- `get_task_engine_state`
- `get_project_options_json`
- `get_user_options_json`
- `list_proxy_request_listeners`
- `get_project_scope_rules`
- `set_project_options_json`
- `set_user_options_json`
- `get_active_text_editor_contents`
- `set_active_text_editor_contents`

### Burp Pro only
- `start_scanner_crawl`
- `start_scanner_audit`
- `get_scanner_task_status`
- `list_scanner_tasks`
- `cancel_scanner_task`
- `generate_scanner_report`
- `list_scanner_issues`
- `generate_collaborator_payload`
- `list_collaborator_interactions`

`list_scanner_issues` defaults:
- `include_detail=false`
- `include_remediation=false`
- set them to `true` when you explicitly need full descriptive text

`list_scanner_issues` key inputs:
- pagination: `limit`, `offset`
- filters: `severity[]`, `confidence[]`, `name_regex`, `url_regex`
- optional enrichment: `include_definition`, `include_request_response`, `max_request_responses`
- optional body shaping for attached request/response snapshots: `serialization`

`list_scanner_issues` output normalization:
- `severity`, `confidence`, `typical_severity` are returned in lower-case wire format
  (`high`, `medium`, `low`, `information`, `false_positive`; `certain`, `firm`, `tentative`)

`list_scanner_tasks` limitation:
- returns only tasks tracked by Awesome MCP in current MCP runtime
- includes only tasks started via this MCP instance (`start_scanner_crawl` / `start_scanner_audit`)
- does not enumerate all scanner tasks that may exist in Burp outside MCP tracking

Collaborator contract:
- `generate_collaborator_payload` returns `payload`, `payload_id`, `server`, `secret_key`
- `custom_data` must match `^[A-Za-z0-9]{1,16}$` (Burp Collaborator runtime limit)
- `list_collaborator_interactions` accepts:
  `payload_id` (tracked payload in current session), or
  `payload` (+ optional `secret_key`) for restart-safe polling
- collaborator client secret + tracked payload map are persisted in extension preferences,
  so polling can continue after extension reload/restart

## Contract conventions

- input/output keys: `snake_case`
- tool names: `snake_case`
- unknown fields rejected (`ignoreUnknownKeys=false`)
- enum values strict; decode errors are normalized with allowed values

Example enum error:
`invalid value 'qwerty' for 'text_overflow_mode'; allowed: [truncate, omit]`

## Query model

Cursor-list response shape (`list_proxy_http_history`, `list_proxy_websocket_history`, `list_site_map`, `list_organizer_items`):
- `total`
- `next` (full next request object, or `null`)
- `results`

Offset-list response shape (`list_scanner_issues`, `list_cookie_jar`):
- `total`
- `returned`
- `offset`
- `limit`
- `has_more`
- `results`

`get_*` response shape:
- `requested`
- `found`
- `results` with per-id/per-key success or error

Site Map key contract:
- `list_site_map` returns `results[].key` (24-char hex stable key)
- `get_site_map_by_keys.keys` must contain exactly these key values
- synthetic strings like `METHOD URL::...` are intentionally not supported

Defaults:
- history/site_map/websocket/organizer queries default to `filter.in_scope_only=true`
- `start_id` defaults to `0` (history/websocket/organizer)
- `id_direction` defaults to `increasing` (history/websocket/organizer)
  - `increasing`: walk toward larger IDs
  - `decreasing`: walk toward smaller IDs
- `start_id` semantics (history/websocket/organizer):
  - `start_id >= 0` is an inclusive Burp ID anchor
  - `start_id < 0` uses python-like index from tail (`-1` last item, `-2` previous)
  - with `id_direction=decreasing`, `start_id=0` starts from the last item
- `start_after_key` defaults to `null` (site map)

Structured `filter` is supported in:
- `list_proxy_http_history`
- `list_proxy_websocket_history`
- `list_site_map`
- `list_organizer_items`

HTTP/Site Map `filter` fields:
- `in_scope_only` (boolean, default `true`)
- `regex` (search across request/response content and annotations)
- `methods` (array, case-insensitive)
- `host_regex`
- `mime_types` (array)
- `inferred_mime_types` (array)
- `status_codes` (array)
- `has_response` (boolean)
- `time_from` / `time_to` (ISO-8601 / RFC3339 timestamp)

`mime_types` matching notes:
- matches explicit MIME signals first: `response.mime_type`, `response.stated_mime_type`, and `Content-Type`
- for `Content-Type`, both full value (for example `APPLICATION/JSON`) and subtype aliases (for example `JSON`) are accepted
- `response.inferred_mime_type` is used only as fallback when explicit MIME signals are unavailable
- `inferred_mime_types` is a dedicated strict filter for `response.inferred_mime_type`

Regex escaping note:
- in JSON input, backslash must be escaped; for literal dot use `ads\\.`.

WebSocket `filter` fields:
- `in_scope_only` (boolean, default `true`)
- `regex` (search across message content and annotations)
- `direction` (`client_to_server` | `server_to_client`)
- `web_socket_ids` (array of websocket IDs)
- `host_regex`
- `listener_ports`
- `has_edited_payload` (boolean)
- `time_from` / `time_to` (ISO-8601 / RFC3339 timestamp)

Paging behavior:
- queries always walk the full Burp list in `id_direction` order
- `total` is the full list size before `filter` is applied
- HTTP/WebSocket/Organizer use inclusive `start_id` anchor + `id_direction`
- Site Map uses `start_after_key` cursor (exclusive key-based)
- `next` is always present: full follow-up query payload or explicit `null`

## Serialization model

### HTTP serialization controls
All HTTP serialization controls are inside `serialization`:
- `serialization.include_headers`
- `serialization.include_request_body`
- `serialization.include_response_body`
- `serialization.include_raw_request`
- `serialization.include_raw_response`
- `serialization.include_binary`
- `serialization.max_text_body_chars` (fallback limit)
- `serialization.max_request_body_chars` (optional override)
- `serialization.max_response_body_chars` (optional override)
- `serialization.text_overflow_mode` = `omit` | `truncate`
- `serialization.max_binary_body_bytes`

Important:
- these fields do **not** filter history/site map/query results
- they only control how request/response payloads are serialized in tool output
- if `serialization` is omitted, defaults are used
- if `serialization` is `{}`, defaults are also used

Default overflow policy:
- `text_overflow_mode = omit`
- `max_text_body_chars = 1024`
- `max_binary_body_bytes = 65536` (64 KB)

That means very large JS/HTML/text responses are omitted by default instead of returning low-signal truncated chunks.

### WebSocket serialization controls
- `serialization.include_binary`
- `serialization.include_edited_payload`
- `serialization.max_text_payload_chars`
- `serialization.max_binary_payload_bytes`

Important:
- these fields do **not** filter selected WebSocket messages
- they only control payload serialization in output

### Binary behavior
- by default (`include_binary=false`), binary body is omitted with reason
- to force binary return, set `include_binary=true`

Example (history query):
```json
{
  "limit": 20,
  "filter": {
    "in_scope_only": true
  },
  "serialization": {
    "include_binary": true,
    "max_response_body_chars": 3000,
    "text_overflow_mode": "omit"
  }
}
```

## Logging

Awesome MCP logs compact activity to:
- Burp Output (`logToOutput`)
- Burp Event log (`raiseDebugEvent` for normal flow, `raiseErrorEvent` for failures)

Log payload includes:
- `type`
- `name`
- `status`
- `ms`
- `req` (sanitized: heavy blob fields masked/sampled)
- optional `note`, `resp_bytes`

## Build

```bash
./gradlew buildWithTests
```

Sandbox-friendly variant:
```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew buildWithTests
```

Artifact:
- `build/libs/burp-awesome-mcp.jar`

Predefined build profiles:
```bash
# 1) build only (no tests)
./gradlew buildPlugin

# 2) build + regular tests
./gradlew buildWithTests

# 3) build + integration tests
./gradlew buildWithIntegrationTests

# 4) linters + formatters
./gradlew lintAndFormat
```

Lint/format:
```bash
./gradlew ktlintCheck
./gradlew ktlintFormat
```

Debug build switch:
- default build is release-like and suppresses MCP Debug events in Burp Event log
- Burp Output logging stays enabled in all builds
- pass `-Pawesome.debug=true` to enable MCP debug events in Burp Event log
- example:
```bash
./gradlew buildPlugin -Pawesome.debug=true
```

## CI

GitHub Actions workflow:
- `.github/workflows/ci.yml`

CI runs on push/PR/workflow_dispatch and executes:
```bash
./gradlew clean test integrationTest ktlintCheck shadowJar
```

CI publishes build artifacts:
- `build/libs/burp-awesome-mcp.jar`
- `skills/SKILL.md`
- `skills/SKILL.t.md`
- `BappManifest.bmf`
- `BappDescription.html`

## Release

GitHub release workflow:
- `.github/workflows/release.yml`

Release trigger:
- push tag matching `v*` (for example `v1.2.3`)

Immutable release mode:
- workflow publishes through `caido/action-release` with `immutableCreate=true`

Release workflow verifies:
- tag format is `v<major>.<minor>.<patch>`
- `gradle.properties` version matches tag version
- full verification build passes (`test`, `integrationTest`, `ktlintCheck`, `shadowJar`)

Release assets include:
- versioned jar: `burp-awesome-mcp-<version>.jar`
- `SKILL.md` and `SKILL.t.md`
- `BappManifest.bmf`
- `BappDescription.html`
- `README.md`
- `LICENSE`
- `SHA256SUMS.txt`
- `awesome-mcp-<version>-bundle.tar.gz`
- optional `.sig` files (when `RELEASE_SIGNING_PRIVATE_KEY` secret is configured)

Optional signing secret:
- `RELEASE_SIGNING_PRIVATE_KEY` (PEM private key used by `openssl pkeyutl -sign`)

Version bump and tagging helper:
```bash
# patch bump, run checks, commit+tag, push
./scripts/release.sh --part patch --push

# explicit version, commit+tag, push and create GH release via gh cli
./scripts/release.sh --version 1.2.3 --push --create-gh-release
```

Release flow recommendation:
- preferred: push tag and let `.github/workflows/release.yml` publish immutable release assets
- optional fallback: `--create-gh-release` for manual local release creation via `gh` CLI

Useful flags:
- `--skip-checks`
- `--allow-dirty`
- `--dry-run`
- `--remote <name>`

## Load into Burp

1. `Extensions` -> `Add`
2. `Extension Type` = `Java`
3. select `build/libs/burp-awesome-mcp.jar`
4. open `Awesome MCP` tab
5. toggle `Enabled`
6. adjust settings and click `Apply` if needed

## Tests

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --rerun-tasks
```
This runs regular tests only (classes matching `*IntegrationTest` are excluded).

Run non-live integration tests:
```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew integrationTest --rerun-tasks
```

Live Burp integration tests (against a running Burp with Awesome MCP enabled):
```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew liveBurpTest \
  --tests "*LiveBurpMcpIntegrationTest" \
  -Dawesome.mcp.live.url=http://127.0.0.1:26001/mcp \
  -Dawesome.mcp.live.transport=streamable_http
```

Optional custom prefixes for live scope assertions:
```bash
-Dawesome.mcp.live.scope.include_prefix=my-include-prefix \
-Dawesome.mcp.live.scope.exclude_prefix=my-exclude-prefix
```

If you want to run live test over SSE instead:
```bash
-Dawesome.mcp.live.url=http://127.0.0.1:26001 \
-Dawesome.mcp.live.transport=sse
```

Run all integration tests including live in one pass:
```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew buildWithIntegrationTests \
  -Pawesome.live=true \
  -Dawesome.mcp.live.url=http://127.0.0.1:26001/mcp \
  -Dawesome.mcp.live.transport=streamable_http
```

Note:
- `buildWithIntegrationTests` now auto-includes `LiveBurpMcpIntegrationTest` when either
  `AWESOME_MCP_LIVE_URL` or `-Dawesome.mcp.live.url=...` is provided.
- `-Pawesome.live=true` is still supported but no longer required in that case.

Generated logs:
- `tests_log.txt` — full test execution + per-test output
- `test-artifacts/mcp-integration-trace.log` — MCP integration request/response trace

## IntelliJ IDEA profiles

1. Open `Run | Edit Configurations...`
2. Add new configuration: `Gradle`
3. Set:
   - `Gradle project`: `burp-awesome-mcp`
   - `Run`: task name from below
   - `Environment`: `GRADLE_USER_HOME=/home/vel/mcp-server-burp/.gradle`
4. Create these four configs:
   - `Build only` -> `buildPlugin --rerun-tasks`
   - `Build + tests` -> `buildWithTests --rerun-tasks`
   - `Build + integration` -> `buildWithIntegrationTests --rerun-tasks`
   - `Lint + format` -> `lintAndFormat --rerun-tasks`

Optional live profile:
- name: `Live Burp test (Streamable HTTP)`
- task: `liveBurpTest --rerun-tasks`
- VM options:
  `-Dawesome.mcp.live.url=http://127.0.0.1:26001/mcp -Dawesome.mcp.live.transport=streamable_http`

## BApp metadata

Project includes:
- `BappManifest.bmf`
- `BappDescription.html`
- `LICENSE` (MIT)

Current author metadata:
- `vvvvvvvvvvel`

## Security and operational notes

- No approval-gate logic in v1 by design.
- Scanner execution orchestration is intentionally not exposed (issue querying only).
- Collaborator and scanner tools are guarded by Burp edition check.
- Do not expose Awesome MCP listener to untrusted networks.

## License

MIT. See [LICENSE](LICENSE).
