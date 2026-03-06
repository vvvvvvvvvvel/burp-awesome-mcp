# Manual Test Cases: Cookie Jar Tools

## Covered tools
- `list_cookie_jar`
- `set_cookie_jar_cookie`
- `expire_cookie_jar_cookie`

## Input field reference

### `list_cookie_jar`
- `limit` (int, default `100`): page size.
- `offset` (int, default `0`): pagination offset.
- `order` (`asc|desc`, default `desc`): sort order.
- `domain_regex` (string|null): regex filter for cookie domain.
- `name_regex` (string|null): regex filter for cookie name.
- `include_expired` (bool, default `false`): include expired entries.
- `include_values` (bool, default `false`): include `value` field in result.
- `max_value_chars` (int, default `200`): max value/preview length.

Example:
```json
{
  "limit": 50,
  "offset": 0,
  "order": "desc",
  "domain_regex": "example",
  "name_regex": "session|csrf",
  "include_expired": true,
  "include_values": true,
  "max_value_chars": 200
}
```

### `set_cookie_jar_cookie`
- `name` (string, required)
- `value` (string, required)
- `domain` (string, required)
- `path` (string|null)
- `expiration` (string|null): expiration datetime string.
- `max_value_chars` (int, default `200`): preview truncation limit in response.

### `expire_cookie_jar_cookie`
- `name` (string, required)
- `domain` (string, required)
- `path` (string|null): optional path scoping.

Delete example:
```json
{
  "name": "mcp_test_cookie",
  "domain": "example.com",
  "path": "/"
}
```

## Preconditions
- Burp Cookie Jar contains at least several cookies (or can be populated by testing).

## TC-CJ-001 Query cookie jar defaults
- Tool: `list_cookie_jar`
- Request:
```json
{}
```
- Expected:
1. Response includes `total`, `returned`, `offset`, `limit`, `has_more`, `results`.
2. By default expired cookies are excluded (`include_expired=false`).
3. Values are not fully returned unless requested.

## TC-CJ-002 Query cookie jar with filters and values
- Tool: `list_cookie_jar`
- Request:
```json
{
  "limit": 50,
  "offset": 0,
  "order": "desc",
  "domain_regex": "example",
  "name_regex": "session|csrf",
  "include_expired": true,
  "include_values": true,
  "max_value_chars": 200
}
```
- Expected:
1. Result set respects domain/name regex.
2. Returned entries include preview and value (possibly truncated).

## TC-CJ-003 Set cookie
- Tool: `set_cookie_jar_cookie`
- Request:
```json
{
  "name": "mcp_test_cookie",
  "value": "test-value-123",
  "domain": "example.com",
  "path": "/",
  "expiration": "2030-01-01T00:00:00Z",
  "max_value_chars": 200
}
```
- Expected:
1. Response includes key fields and preview.
2. Follow-up query can find the cookie.

## TC-CJ-004 Set cookie with invalid expiration
- Tool: `set_cookie_jar_cookie`
- Request:
```json
{
  "name": "bad_cookie",
  "value": "v",
  "domain": "example.com",
  "expiration": "not-a-date"
}
```
- Expected:
1. Validation error (`isError=true`) with parse message.

## TC-CJ-005 Delete cookie by name+domain
- Tool: `expire_cookie_jar_cookie`
- Steps:
1. Create cookie via TC-CJ-003.
2. Delete:
```json
{
  "name": "mcp_test_cookie",
  "domain": "example.com",
  "path": "/"
}
```
- Expected:
1. Response includes `deleted` count.
2. Cookie no longer appears in default query.

## TC-CJ-006 Delete cookie domain normalization
- Tool: `expire_cookie_jar_cookie`
- Goal: verify `.example.com` vs `example.com` handling.
- Steps:
1. Set cookie for `.example.com` (or via Burp capture).
2. Delete using `example.com`.
- Expected:
1. Matching cookie is deleted (domain normalization works).

## TC-CJ-007 Delete non-existing cookie
- Tool: `expire_cookie_jar_cookie`
- Request:
```json
{
  "name": "definitely_missing_cookie",
  "domain": "example.com"
}
```
- Expected:
1. Response successful with `deleted: 0`.
2. No extra tombstone noise should be introduced.
