# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-10-27

### Added
- MCP server over stdio, built on the official MCP Java SDK (2.1.0).
- Loads OpenAPI 3.0 / 3.1 documents (and Swagger 2.0, converted by swagger-parser) from a file or URL.
- One MCP tool per operation: name from `operationId` (sanitized, max 64 chars, collisions resolved) or
  `method_path`; description from summary/description; MCP tool annotations (`readOnlyHint`, `destructiveHint`, ...).
- Input schema merging path, query, header and cookie parameters plus the request body (under `body`),
  with `$ref`s inlined, recursion cut off, `required` propagated and OpenAPI-only keywords removed.
- Request execution with `java.net.http.HttpClient`: path/query/header/cookie serialization (incl. exploded
  arrays and `deepObject`), JSON, form-urlencoded and plain-text bodies.
- Safety: read-only (GET/HEAD) by default, `--allow-writes`, `--include` / `--exclude` glob filters on
  operationId, tag or path; static auth headers via `--header` or `OPENAPI_MCP_HEADER_*` that are never logged.
- Timeouts, response size cap with truncation note, binary responses summarized instead of dumped.
- HTTP errors, timeouts and connection failures returned as tool results with `isError: true`.
- `--list-tools` dry run printing the generated tool definitions as JSON.
- Runnable fat jar; every option has an environment variable equivalent.
