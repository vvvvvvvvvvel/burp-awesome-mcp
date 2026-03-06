# Manual Test Cases: Configuration and Runtime Tools

## Covered tools
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

## Input field reference

### Tools with empty request body
- `get_project_options_json`, `get_user_options_json`, `list_proxy_request_listeners`, `get_project_scope_rules`
- `get_task_engine_state`, `get_proxy_intercept_enabled`, `get_active_text_editor_contents`
- Request shape:
```json
{}
```

### `set_project_options_json`
- `json` (string, required): full project options JSON to merge/import.

### `set_user_options_json`
- `json` (string, required): full user options JSON to merge/import.

### `set_task_engine_state`
- `running` (bool, required): `true` to resume task execution, `false` to pause.

### `set_proxy_intercept_enabled`
- `intercepting` (bool, required): enable/disable Proxy Intercept.

### `set_active_text_editor_contents`
- `text` (string, required): full replacement text for currently focused editable Burp component.

Examples:
```json
{ "running": false }
```
```json
{ "intercepting": true }
```
```json
{ "text": "GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n" }
```

## Preconditions
- Burp is running with writable project settings.
- MCP extension is enabled.

## TC-CR-001 Export project options
- Tool: `get_project_options_json`
- Request:
```json
{}
```
- Expected:
1. Returns valid JSON object string.
2. Contains major sections like `project_options`, `proxy`, `target`.

## TC-CR-002 Export user options
- Tool: `get_user_options_json`
- Request:
```json
{}
```
- Expected:
1. Returns valid JSON object string.
2. Contains user-level configuration sections.

## TC-CR-003 Read proxy listeners
- Tool: `list_proxy_request_listeners`
- Request:
```json
{}
```
- Expected:
1. Response includes configured proxy listeners.
2. Each listener exposes host/port/protocol state.

## TC-CR-004 Read project scope rules
- Tool: `get_project_scope_rules`
- Request:
```json
{}
```
- Expected:
1. Response includes include/exclude scope arrays.
2. Reflects latest scope edits done by scope tools.

## TC-CR-005 Set project options (safe no-op roundtrip)
- Tools: `get_project_options_json` + `set_project_options_json`
- Steps:
1. Call `get_project_options_json`.
2. Pass returned JSON back into `set_project_options_json`.
- Request:
```json
{
  "json": "<full project options json>"
}
```
- Expected:
1. `set_project_options_json` succeeds.
2. No corruption of project settings.

## TC-CR-006 Set user options (safe no-op roundtrip)
- Tools: `get_user_options_json` + `set_user_options_json`
- Steps:
1. Call `get_user_options_json`.
2. Pass returned JSON into `set_user_options_json`.
- Expected:
1. `set_user_options_json` succeeds.
2. Burp remains stable.

## TC-CR-007 Task execution engine toggle
- Tools: `set_task_engine_state`, `get_task_engine_state`
- Steps:
1. Set `running=false`.
2. Verify `get_task_engine_state` returns `running=false`.
3. Set `running=true` and verify again.
- Requests:
```json
{
  "running": false
}
```
```json
{
  "running": true
}
```
- Expected:
1. State transitions are reflected correctly.

## TC-CR-008 Proxy intercept toggle
- Tools: `set_proxy_intercept_enabled`, `get_proxy_intercept_enabled`
- Steps:
1. Set `intercepting=true`.
2. Confirm state via `get_proxy_intercept_enabled`.
3. Set `intercepting=false` and confirm.
- Requests:
```json
{
  "intercepting": true
}
```
```json
{
  "intercepting": false
}
```
- Expected:
1. Intercept state matches requested value.

## TC-CR-009 Active editor read
- Tool: `get_active_text_editor_contents`
- Preconditions:
- Focus an editable Burp text area (for example Repeater request editor).
- Request:
```json
{}
```
- Expected:
1. Returns current editor text.
2. If no valid editor focused, deterministic error is returned.

## TC-CR-010 Active editor write
- Tool: `set_active_text_editor_contents`
- Preconditions:
- Focus editable Burp text area.
- Request:
```json
{
  "text": "GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n"
}
```
- Expected:
1. Focused editor content is replaced with provided text.
2. Follow-up `get_active_text_editor_contents` returns updated content.
