# Thymeleaf + HTMX + SpEL UI Development Guide

This is the canonical guide for building web UI in this codebase.

Use this guide for:
- Thymeleaf template syntax and safety
- HTMX request/fragment patterns
- SpEL expression hygiene
- Controller/service generation patterns
- Default index/table UX pattern (pagination, sorting, filtering, inline editing)

## 1) Syntax Guardrails (Non-Negotiable)

### 1.1 Prefer dedicated Thymeleaf HTMX processors
Prefer:
- `th:hx-get`
- `th:hx-post`
- `th:hx-patch`
- `th:hx-target`
- `th:hx-swap`

Use `th:attr` only when no dedicated processor exists.

### 1.2 `hx-on` with `th:attr`
- Static HTML form is fine: `hx-on::after-request="..."`
- Dynamic form in `th:attr` must use double-dash key form:
  - `th:attr="hx-on--after-request=|...|"`
- Do not use `hx-on::...` inside `th:attr`.

### 1.3 `th:attr` safety
- Always use assignation syntax: `name=value, name2=value2`
- Use Thymeleaf literal substitution for mixed strings: `|text ${expr}|`
- Keep long expressions in `th:with` locals.

### 1.4 Reserved names
Do not use these for `th:each` vars, `th:with` vars, fragment args, or `model.addAttribute(...)` keys:
- `session`
- `param`
- `request`
- `response`
- `application`
- `servletContext`

### 1.5 SpEL hygiene
- Null-guard values explicitly when rendering nested properties.
- Avoid deep chained lookups without fallback.
- Prefer precomputed model attributes or `th:with` for repeated logic.

For compact syntax-only notes, also see:
- [thymeleaf-htmx-spel-notes.md](thymeleaf-htmx-spel-notes.md)

## 2) Controller/Service Generation Pattern

### 2.1 List endpoints
For list/index pages, standardize on:
- `filter` query param
- optional feature toggles (for example `showSkipped`)
- Spring `Pageable` with `@SortDefault` and `@PageableDefault`
- `paginationModel` in model via `WebUtils.getPaginationModel(...)`

Example shape:

```kotlin
@GetMapping
fun list(
    @RequestParam(name = "filter", required = false) filter: String?,
    @RequestParam(name = "showSkipped", required = false, defaultValue = "false") showSkipped: Boolean,
    @SortDefault(sort = ["id"]) @PageableDefault(size = 20) pageable: Pageable,
    model: Model
): String {
    val page = service.findAll(filter, pageable, showSkipped)
    model.addAttribute("items", page)
    model.addAttribute("filter", filter)
    model.addAttribute("showSkipped", showSkipped)
    model.addAttribute("paginationModel", WebUtils.getPaginationModel(page))
    return "entity/list"
}
```

### 2.2 HTMX partial/full responses
For pages that support both full-page and HTMX fragment refresh:
- return full template for normal and `HX-Boosted` requests
- return fragment only for non-boosted HTMX

```kotlin
val isHtmx = request.getHeader("HX-Request") == "true"
val isBoosted = request.getHeader("HX-Boosted") == "true"
return if (isHtmx && !isBoosted) "entity/list :: table-content" else "entity/list"
```

### 2.3 Lazy-loading/LazyInitialization safety
Do not let templates trigger lazy entity navigation outside transaction scope.

Preferred order:
1. Return DTOs prepared in service layer.
2. Use repository `JOIN FETCH`/`LEFT JOIN FETCH` when relation data is required.
3. Keep transactional boundaries in service methods, not in templates.
4. If forced, initialize needed collections before leaving transaction.

## 3) Default Index/Table UX Pattern (95% Case)

### 3.1 Page structure
Use this layout order:
1. Header row: title, count, primary action(s)
2. Controls row: search/filter/sort/toggles
3. Table container (`#table-content`) for HTMX swaps
4. Empty state when no rows
5. Pagination footer

### 3.2 Controls behavior
- Search input: `hx-trigger="input changed delay:300ms, search"`
- Filters/sorts: `th:hx-get` + `hx-target="#table-content"`
- Preserve state: `hx-push-url="true"`
- Send all control values with each interaction: `hx-include="..."`

### 3.3 Sorting behavior
- Offer both dropdown sorting and/or clickable sortable headers.
- Show active sort and direction indicator.
- Keep sort param stable (`field,DESC` or `field,ASC`).

### 3.4 Pagination behavior
- Always render `paginationModel` using shared fragment.
- Pagination links must preserve filter/sort params.

### 3.5 Inline editing behavior
Use click-to-edit with HTMX fragment swaps:
- display fragment (`.../display`)
- edit fragment (`.../edit`)
- patch endpoint (`.../{field}`)
- `hx-swap="outerHTML"`
- keyboard behavior:
  - Enter: save
  - Escape: cancel
  - Blur: save for simple inputs

### 3.6 Visual rules
- Use consistent muted/secondary style for `SKIP` rows and badges.
- Keep row actions compact and right-aligned.
- Keep column widths intentional for dense tables.

## 4) Reference Implementations In This Repo

- Shared pagination/sorting/filter fragments:
  - [src/main/resources/templates/fragments/utils.html](../../src/main/resources/templates/fragments/utils.html)
- Inline editing fragments + JS behavior:
  - [src/main/resources/templates/crawlConfig/fragments/inlineEdit.html](../../src/main/resources/templates/crawlConfig/fragments/inlineEdit.html)
- Basic list page pattern:
  - [src/main/resources/templates/fSFile/list.html](../../src/main/resources/templates/fSFile/list.html)
- Advanced HTMX table pattern (search/filter/sort/toggle + fragment swaps):
  - [src/main/resources/templates/crawlConfig/files.html](../../src/main/resources/templates/crawlConfig/files.html)
- Controller list pattern:
  - [src/main/kotlin/com/oconeco/spring_search_tempo/base/controller/FSFileController.kt](../../src/main/kotlin/com/oconeco/spring_search_tempo/base/controller/FSFileController.kt)
- HTMX full-vs-fragment response handling:
  - [src/main/kotlin/com/oconeco/spring_search_tempo/web/controller/CrawlConfigController.kt](../../src/main/kotlin/com/oconeco/spring_search_tempo/web/controller/CrawlConfigController.kt)

## 5) PR Checklist For UI Changes

- [ ] No reserved Thymeleaf names used (`session`, `param`, etc.)
- [ ] No `hx-on::...` inside `th:attr`
- [ ] DTO/fetch strategy avoids lazy-init template failures
- [ ] Filter/sort/page state preserved in URL
- [ ] Empty state and pagination both verified
- [ ] Inline edit supports save/cancel keyboard behavior
- [ ] At least one integration test or manual smoke test on affected page
