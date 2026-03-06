# Manual Test Cases: Scanner and Collaborator Tools (Burp Pro)

## Covered tools
- `start_scanner_crawl`
- `start_scanner_audit`
- `get_scanner_task_status`
- `list_scanner_tasks`
- `cancel_scanner_task`
- `generate_scanner_report`
- `list_scanner_issues`
- `generate_collaborator_payload`
- `list_collaborator_interactions`

## Input field reference

### `start_scanner_crawl`
- `seed_urls` (string[], required): initial URLs for crawl.

### `start_scanner_audit`
- `preset` (`passive_audit_checks|active_audit_checks`, default `passive_audit_checks`).
- `urls` (string[], default `[]`): optional initial URLs.

### `get_scanner_task_status` / `cancel_scanner_task`
- `task_id` (string, required): scanner task identifier returned by start APIs.

### `list_scanner_tasks`
- no input fields.
- limitation: returns only tasks tracked by Awesome MCP in current MCP runtime.
- limitation: includes only tasks started via this MCP instance.
- limitation: does not enumerate all scanner tasks that may exist in Burp outside MCP tracking.

### `list_scanner_issues`
- `limit` (int, default `20`)
- `offset` (int, default `0`)
- `severity` (`high|medium|low|information|false_positive`[]|null)
- `confidence` (`certain|firm|tentative`[]|null)
- `name_regex` (string|null)
- `url_regex` (string|null)
- `include_detail` (bool, default `false`)
- `include_remediation` (bool, default `false`)
- `include_definition` (bool, default `false`)
- `include_request_response` (bool, default `false`)
- `max_request_responses` (int, default `3`)
- `serialization` (object; default keeps headers and omits request/response bodies):
  - `include_headers` (bool, default `true`)
  - `include_request_body` (bool, default `false` for this tool)
  - `include_response_body` (bool, default `false` for this tool)
  - `include_raw_request` (bool, default `false`)
  - `include_raw_response` (bool, default `false`)
  - `include_binary` (bool, default `false`)
  - `max_text_body_chars` (int, default `1024`)
  - `max_request_body_chars` (int|null)
  - `max_response_body_chars` (int|null)
  - `text_overflow_mode` (`truncate|omit`, default `omit`)
  - `max_binary_body_bytes` (int, default `65536`)

`list_scanner_issues` filter example:
```json
{
  "limit": 20,
  "offset": 0,
  "severity": ["high", "medium"],
  "confidence": ["certain", "firm"],
  "name_regex": "sql|xss",
  "url_regex": "example",
  "include_detail": true,
  "include_remediation": true,
  "include_definition": true,
  "include_request_response": true,
  "max_request_responses": 2,
  "serialization": {
    "include_headers": true,
    "include_request_body": false,
    "include_response_body": false,
    "text_overflow_mode": "omit"
  }
}
```

### `generate_scanner_report`
- `output_file` (string, required): destination path.
- `format` (`html|xml`, default `html`).
- `severity` (`high|medium|low|information|false_positive`[]|null)
- `confidence` (`certain|firm|tentative`[]|null)
- `name_regex` (string|null)
- `url_regex` (string|null)

### `generate_collaborator_payload`
- `custom_data` (string|null): optional marker, `1..16` chars, alphanumeric.

### `list_collaborator_interactions`
- `payload_id` (string|null): preferred when payload generated in current runtime.
- `payload` (string|null): payload URL fallback.
- `secret_key` (string|null): needed with `payload` fallback.

Collaborator polling examples:
```json
{ "payload_id": "<payload-id>" }
```
```json
{ "payload": "<payload-url>", "secret_key": "<secret-key>" }
```

## Preconditions
- Burp Professional license active.
- Test only on authorized targets.
- For Collaborator checks, outbound DNS/HTTP is allowed.

## TC-SC-001 Start scanner crawl
- Tool: `start_scanner_crawl`
- Request:
```json
{
  "seed_urls": [
    "https://example.com/"
  ]
}
```
- Expected:
1. Response contains `task_id`.
2. Task appears in Burp task engine.

## TC-SC-002 Start scanner audit
- Tool: `start_scanner_audit`
- Request:
```json
{
  "preset": "passive_audit_checks",
  "urls": [
    "https://example.com/"
  ]
}
```
- Expected:
1. Response contains `task_id`.
2. Audit task starts successfully.

## TC-SC-003 Get scanner task status
- Tool: `get_scanner_task_status`
- Steps:
1. Start crawl or audit and capture `task_id`.
2. Request:
```json
{
  "task_id": "<task-id>"
}
```
- Expected:
1. Status object includes `task_type`, `status_message`, counters.
2. Repeated polling updates progress counters.

## TC-SC-004 Cancel scanner task
- Tool: `cancel_scanner_task`
- Steps:
1. Start a long scan task.
2. Cancel it:
```json
{
  "task_id": "<task-id>"
}
```
- Expected:
1. Response indicates `deleted=true` for active task.
2. Follow-up status call returns not found or stopped state.

## TC-SC-005 List tracked scanner tasks
- Tool: `list_scanner_tasks`
- Request:
```json
{}
```
- Expected:
1. Response includes `total` and `results`.
2. `results[*].task_id` contains only MCP-tracked scanner task IDs (for example `crawl-1`, `audit-2`).
3. Tool does not claim to list unrelated Burp scanner tasks.

## TC-SC-006 Query scanner issues default mode
- Tool: `list_scanner_issues`
- Request:
```json
{}
```
- Expected:
1. Response has `total`, `returned`, `offset`, `limit`, `has_more`, `results`.
2. `results[*].severity` and `confidence` are lower-case contract values.
3. No null-pointer failures when issue detail fields are absent.

## TC-SC-007 Query scanner issues with filters and enrichment
- Tool: `list_scanner_issues`
- Request:
```json
{
  "limit": 20,
  "offset": 0,
  "severity": ["high", "medium"],
  "confidence": ["certain", "firm"],
  "name_regex": "sql|xss",
  "url_regex": "example",
  "include_detail": true,
  "include_remediation": true,
  "include_definition": true,
  "include_request_response": true,
  "max_request_responses": 2,
  "serialization": {
    "include_headers": true,
    "include_request_body": false,
    "include_response_body": false,
    "text_overflow_mode": "omit"
  }
}
```
- Expected:
1. Filtered issue subset is returned.
2. Optional fields appear when enabled.
3. Request/response attachments respect serialization settings.

## TC-SC-008 Generate scanner report
- Tool: `generate_scanner_report`
- Request:
```json
{
  "output_file": "/tmp/awesome-mcp-scanner-report.html",
  "format": "html",
  "severity": ["high", "medium"],
  "confidence": ["certain", "firm"],
  "name_regex": "",
  "url_regex": ""
}
```
- Expected:
1. Report generation succeeds.
2. File exists at specified path.
3. `included_issues` matches expected filtered count.

## TC-SC-009 Generate collaborator payload
- Tool: `generate_collaborator_payload`
- Request:
```json
{
  "custom_data": "A1B2C3"
}
```
- Expected:
1. Response includes `payload`, `payload_id`, `server`, `secret_key`.
2. `custom_data` constraints enforced (`1..16`, alphanumeric).

## TC-SC-010 Poll collaborator interactions by payload_id
- Tool: `list_collaborator_interactions`
- Steps:
1. Generate payload (TC-SC-009).
2. Trigger external interaction to payload URL.
3. Poll using payload ID:
```json
{
  "payload_id": "<payload-id>"
}
```
- Expected:
1. Response contains `count` and `interactions` list.
2. Interaction fields include `id`, `type`, `timestamp`, `client_ip`, `client_port`.

## TC-SC-011 Poll collaborator interactions by payload + secret_key
- Tool: `list_collaborator_interactions`
- Request:
```json
{
  "payload": "<payload-url>",
  "secret_key": "<secret-key>"
}
```
- Expected:
1. Works without `payload_id` if payload and secret key are valid.
2. Useful for restart-safe polling path.
