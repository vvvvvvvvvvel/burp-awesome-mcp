# Awesome MCP Manual Test Plan and Coverage

## Purpose
This document defines the manual test strategy for Awesome MCP and maps every tool to an executable test-case file. It is written for a human QA engineer using Burp Suite + MCP Inspector.

## Test Environment
- Burp Suite Professional (latest available build).
- Awesome MCP extension loaded and enabled.
- MCP transport: `streamable_http`.
- MCP endpoint: `http://127.0.0.1:26001/mcp`.
- MCP Inspector connected to that endpoint.
- A browser configured to use Burp Proxy listener.

## Baseline Burp Project Preconditions
Before running tool-specific cases, prepare baseline data once:
1. Open several websites through Burp-proxied browser (for example: `https://example.com`, `https://portswigger.net`, and one target in scope).
2. Ensure Proxy HTTP history contains at least 30 entries with mixed methods/status codes.
3. Add at least one in-scope rule and ensure in-scope and out-of-scope traffic both exist.
4. Create at least one Repeater tab manually (for editor/tool checks).
5. If WebSocket tests are required, browse an app with active WebSocket traffic.
6. If scanner tests are required, run only on allowed test targets.

## MCP Inspector Conventions
- Use snake_case fields exactly as shown in examples.
- If a field is omitted, tool defaults apply.
- For regex in JSON, escape backslashes (example: `ads\\.`).
- Record for each test case:
  - request JSON
  - full MCP response
  - pass/fail verdict
  - notes/screenshots if behavior differs

## Exit Criteria
- All tool cases pass.
- No contract mismatch between schema and runtime behavior.
- No unexpected MCP errors for valid inputs.
- Invalid inputs return deterministic validation errors.

## Tool Coverage Matrix

### History and retrieval
- `list_proxy_http_history` -> `01_history_http_tools.md`
- `get_proxy_http_history_by_ids` -> `01_history_http_tools.md`
- `list_proxy_websocket_history` -> `02_history_websocket_tools.md`
- `get_proxy_websocket_messages_by_ids` -> `02_history_websocket_tools.md`
- `list_site_map` -> `03_site_map_and_organizer_tools.md`
- `get_site_map_by_keys` -> `03_site_map_and_organizer_tools.md`
- `summarize_http_history_cookies` -> `01_history_http_tools.md`
- `summarize_http_history_auth_headers` -> `01_history_http_tools.md`

### Active HTTP and workflow
- `send_http1_requests` -> `04_active_http_and_workflow_tools.md`
- `send_http2_requests` -> `04_active_http_and_workflow_tools.md`
- `create_repeater_tabs` -> `04_active_http_and_workflow_tools.md`
- `send_requests_to_intruder` -> `04_active_http_and_workflow_tools.md`
- `send_requests_to_intruder_template` -> `04_active_http_and_workflow_tools.md`
- `send_requests_to_organizer` -> `03_site_map_and_organizer_tools.md`
- `list_organizer_items` -> `03_site_map_and_organizer_tools.md`
- `get_organizer_items_by_ids` -> `03_site_map_and_organizer_tools.md`

### Scope and utility
- `scope_add_include` -> `05_scope_and_utility_tools.md`
- `scope_add_exclude` -> `05_scope_and_utility_tools.md`
- `scope_remove_include` -> `05_scope_and_utility_tools.md`
- `scope_remove_exclude` -> `05_scope_and_utility_tools.md`
- `scope_is_url_in_scope` -> `05_scope_and_utility_tools.md`
- `url_encode` -> `05_scope_and_utility_tools.md`
- `url_decode` -> `05_scope_and_utility_tools.md`
- `base64_encode` -> `05_scope_and_utility_tools.md`
- `base64_decode` -> `05_scope_and_utility_tools.md`
- `generate_random_string` -> `05_scope_and_utility_tools.md`

### Burp config and runtime control
- `get_project_options_json` -> `06_configuration_and_runtime_tools.md`
- `get_user_options_json` -> `06_configuration_and_runtime_tools.md`
- `list_proxy_request_listeners` -> `06_configuration_and_runtime_tools.md`
- `get_project_scope_rules` -> `06_configuration_and_runtime_tools.md`
- `set_project_options_json` -> `06_configuration_and_runtime_tools.md`
- `set_user_options_json` -> `06_configuration_and_runtime_tools.md`
- `set_task_engine_state` -> `06_configuration_and_runtime_tools.md`
- `get_task_engine_state` -> `06_configuration_and_runtime_tools.md`
- `set_proxy_intercept_enabled` -> `06_configuration_and_runtime_tools.md`
- `get_proxy_intercept_enabled` -> `06_configuration_and_runtime_tools.md`
- `get_active_text_editor_contents` -> `06_configuration_and_runtime_tools.md`
- `set_active_text_editor_contents` -> `06_configuration_and_runtime_tools.md`

### Burp Pro features
- `start_scanner_crawl` -> `07_scanner_and_collaborator_tools.md`
- `start_scanner_audit` -> `07_scanner_and_collaborator_tools.md`
- `get_scanner_task_status` -> `07_scanner_and_collaborator_tools.md`
- `cancel_scanner_task` -> `07_scanner_and_collaborator_tools.md`
- `generate_scanner_report` -> `07_scanner_and_collaborator_tools.md`
- `list_scanner_issues` -> `07_scanner_and_collaborator_tools.md`
- `generate_collaborator_payload` -> `07_scanner_and_collaborator_tools.md`
- `list_collaborator_interactions` -> `07_scanner_and_collaborator_tools.md`

### Cookie Jar
- `list_cookie_jar` -> `08_cookie_jar_tools.md`
- `set_cookie_jar_cookie` -> `08_cookie_jar_tools.md`
- `expire_cookie_jar_cookie` -> `08_cookie_jar_tools.md`
