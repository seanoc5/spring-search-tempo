# AGENTS.md

Concise repo-local instructions for coding agents working in Spring Search Tempo.

Use [CLAUDE.md](/opt/work/springboot/spring-search-tempo/CLAUDE.md) for deeper project context. This file focuses on high-value guardrails that prevent common regressions.

## Tooling

- Prefer focused CLI tools first: `rg`, `sed`, `awk`, `jq`.
- Use scripting only when CLI tools are not a good fit.
- If scripting is required, prefer `python` over `perl`.
- Favor the least-capable tool that safely solves the task.

## Backend-First Fixes

- Prefer backend fixes over frontend/template duct tape.
- Controllers and services should provide safe, ready-to-render model data.
- Keep filtering, joins, inheritance calculations, and derived display state out of templates when practical.

## JPA + Thymeleaf

- Before writing controller code, identify every entity relationship the template touches.
- Use `JOIN FETCH`, entity graphs, or DTO projections for anything the template accesses.
- Do not rely on SpEL safe navigation (`?.`) to avoid `LazyInitializationException`; it only handles nulls.
- When mapping entities to DTOs, explicitly ignore lazy collections in MapStruct unless they are intentionally loaded.

## Thymeleaf / HTMX / SpEL Guardrails

- Do not use Thymeleaf reserved web-context names for local vars, fragment args, or model keys:
  - `session`, `param`, `request`, `response`, `application`, `servletContext`
- Do not use those names in:
  - `th:each`
  - `th:with`
  - fragment parameters
  - `model.addAttribute(...)`
- Do not use `#request`, `#session`, `#response`, or similar web-context objects in templates; pass needed state via model attributes instead.
- Typical failure signature:
  - `Cannot set variable called 'session' into web variables map: such name is a reserved word`
- Never use `th:on*` handlers with string expressions; use `data-*` attributes plus delegated JavaScript.
- `param.*` values are array-backed; use explicit null checks like `${param.expired != null || param.sessionExpired != null}`.
- Prefer `th:hx-*` processors where available.
- In `th:attr`, use `hx-on--...` keys instead of `hx-on::...`.
- For non-boosted HTMX requests, return fragments.
- For normal requests and `HX-Boosted` requests, return full pages or redirects.
- If using `hx-target` with `hx-swap="outerHTML"`, the returned fragment root must render the same stable target id/selector.
- Do not return a `th:block` root when the client expects to swap a concrete container element.

## Kotlin Naming

- Avoid Kotlin keywords like `class`, `object`, `when`, and `is` for DTO/entity field names unless absolutely required.
- Backticked identifiers increase binding and template risk; prefer renaming instead.

## Quick Checks

- Before committing template/controller changes, scan for reserved-name collisions:
  - `rg -n 'th:each="(session|param|request|response|application|servletContext)\\s*:|th:with="(session|param|request|response|application|servletContext)\\s*=|addAttribute\\("(session|param|request|response|application|servletContext)"' src/main`
