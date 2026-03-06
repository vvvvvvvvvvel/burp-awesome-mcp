# Manual Test Cases: Scope and Utility Tools

## Covered tools
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

## Input field reference

### Scope tools
- `scope_add_include`
  - `url` (string, required): absolute URL, host, `host:port`, or short prefix.
  - `include_subdomains` (bool, default `false`): add host-style include rule for subdomains.
- `scope_add_exclude`
  - `url` (string, required)
  - `include_subdomains` (bool, default `false`)
- `scope_remove_include`
  - `url` (string, required)
  - `include_subdomains` (bool|null, default `null`): remove all matching variants if omitted.
- `scope_remove_exclude`
  - `url` (string, required)
  - `include_subdomains` (bool|null, default `null`)
- `scope_is_url_in_scope`
  - `url` (string, required)

Scope request examples:
```json
{ "url": "example.org", "include_subdomains": true }
```
```json
{ "url": "https://example.org/private" }
```

### Encoding tools
- `url_encode`, `url_decode`, `base64_encode`, `base64_decode`
  - `items` (array, required):
    - `content` (string): source text/blob representation.

Encoding request example:
```json
{
  "items": [
    { "content": "a b+c&d" },
    { "content": "hello mcp" }
  ]
}
```

### `generate_random_string`
- `length` (int, required): output size; must be positive.
- `character_set` (string, required): allowed character alphabet; must be non-empty.

Example:
```json
{
  "length": 24,
  "character_set": "abcdef012345"
}
```

## Preconditions
- Burp project scope is editable.
- MCP endpoint connected.

## TC-SU-001 Add include scope prefix
- Tool: `scope_add_include`
- Request:
```json
{
  "url": "example.com",
  "include_subdomains": false
}
```
- Expected:
1. Response includes `url`, `in_scope`, `include_subdomains`, `scope_rule_updated`.
2. Rule appears in project scope include list.

## TC-SU-002 Add include scope with subdomains
- Tool: `scope_add_include`
- Request:
```json
{
  "url": "example.org",
  "include_subdomains": true
}
```
- Expected:
1. Host-style include rule is created with subdomain flag.
2. `scope_rule_updated=true` when new.

## TC-SU-003 Add exclude scope prefix
- Tool: `scope_add_exclude`
- Request:
```json
{
  "url": "example.com/private",
  "include_subdomains": false
}
```
- Expected:
1. Exclude rule is added.
2. `scope_rule_updated=true` for a new rule.

## TC-SU-004 Remove include with selector
- Tool: `scope_remove_include`
- Request:
```json
{
  "url": "example.org",
  "include_subdomains": true
}
```
- Expected:
1. Only matching variant is removed.
2. `scope_rule_updated=true` when removal happened.

## TC-SU-005 Remove exclude rule
- Tool: `scope_remove_exclude`
- Request:
```json
{
  "url": "example.com/private"
}
```
- Expected:
1. Matching exclude rule is removed.
2. Re-running returns `scope_rule_updated=false`.

## TC-SU-006 Check scope status
- Tool: `scope_is_url_in_scope`
- Request:
```json
{
  "url": "https://example.com/"
}
```
- Expected:
1. Response returns boolean `in_scope`.
2. Value matches Burp Target scope state.

## TC-SU-007 Invalid scope URL input
- Tool: `scope_add_include`
- Request:
```json
{
  "url": "!"
}
```
- Expected:
1. `isError=true`.
2. Error explains invalid input (no letter/digit).

## TC-SU-008 URL encode/decode roundtrip
- Tools: `url_encode`, `url_decode`
- Requests:
```json
{
  "items": [
    {
      "content": "a b+c&d"
    }
  ]
}
```
Then feed encoded output to `url_decode`.
- Expected:
1. Encode succeeds.
2. Decode returns original string.

## TC-SU-009 Base64 encode/decode text
- Tools: `base64_encode`, `base64_decode`
- Request (encode):
```json
{
  "items": [
    {
      "content": "hello mcp"
    }
  ]
}
```
- Expected:
1. Encoded value is valid base64.
2. Decoding returns text with `encoding: "utf8"` or equivalent textual marker.

## TC-SU-010 Base64 decode invalid input
- Tool: `base64_decode`
- Request:
```json
{
  "items": [
    {
      "content": "***"
    }
  ]
}
```
- Expected:
1. Per-item result has `ok=false` with decode error.

## TC-SU-011 Generate random string
- Tool: `generate_random_string`
- Request:
```json
{
  "length": 24,
  "character_set": "abcdef012345"
}
```
- Expected:
1. Output length is exactly 24.
2. Characters belong only to provided set.
3. Invalid parameters (length <= 0, empty charset) return validation error.
