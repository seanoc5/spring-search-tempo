# Portable Thymeleaf/HTMX Style Pack (For Other Spring Boot Kotlin Apps)

Use this pack to standardize 5-10 apps quickly.

## 1) Add this to each app's AGENTS/CLAUDE-style doc

```md
## Thymeleaf + HTMX + SpEL Rules

- Prefer `th:hx-*` processors over `th:attr` for HTMX attributes.
- In `th:attr`, never use `hx-on::...`; use `hx-on--...` key form.
- Keep `th:attr` values valid assignation sequences (`name=value, name2=value2`).
- Do not use reserved Thymeleaf names (`session`, `param`, `request`, `response`, `application`, `servletContext`) for model keys or local vars.
- Keep SpEL null-safe; avoid deep chained access without guards.

## Controller + Service Pattern

- List endpoints accept `filter`, typed toggles, and `Pageable`.
- Always add `paginationModel` from `WebUtils.getPaginationModel(page)` or equivalent helper.
- For HTMX pages, return fragment for non-boosted HTMX requests; return full page for normal and HX-Boosted requests.
- Avoid LazyInitializationException in templates: use DTOs, transactional service mapping, and JOIN FETCH where needed.

## Default Table UX Pattern (95% case)

- Header row: title, counts, primary action.
- Control row: search, filters, sort, toggles.
- Search input uses HTMX debounce (`input changed delay:300ms, search`).
- Controls use `hx-target="#table-content"`, `hx-push-url="true"`, and `hx-include` to preserve full state.
- Render empty state when no rows.
- Render pagination footer for all paged tables.
- Inline edit uses click-to-edit fragments (`display`, `edit`, `patch`) with `hx-swap="outerHTML"`.
- Keyboard inline-edit rules: Enter saves, Escape cancels.
```

## 2) Drop-in Controller Skeleton

```kotlin
@GetMapping
fun list(
    @RequestParam(name = "filter", required = false) filter: String?,
    @RequestParam(name = "showSkipped", required = false, defaultValue = "false") showSkipped: Boolean,
    @SortDefault(sort = ["id"]) @PageableDefault(size = 20) pageable: Pageable,
    model: Model,
    request: HttpServletRequest
): String {
    val page = service.findAll(filter, pageable, showSkipped)
    model.addAttribute("items", page)
    model.addAttribute("filter", filter)
    model.addAttribute("showSkipped", showSkipped)
    model.addAttribute("paginationModel", WebUtils.getPaginationModel(page))

    val isHtmx = request.getHeader("HX-Request") == "true"
    val isBoosted = request.getHeader("HX-Boosted") == "true"
    return if (isHtmx && !isBoosted) "entity/list :: table-content" else "entity/list"
}
```

## 3) Drop-in Table Controls Skeleton

```html
<div class="row mb-3 align-items-end">
    <div class="col-md-4">
        <label class="form-label">Search</label>
        <input type="search" name="filter"
               th:value="${filter}"
               th:hx-get="@{/entities}"
               hx-trigger="input changed delay:300ms, search"
               hx-target="#table-content"
               hx-push-url="true"
               hx-include="[name='showSkipped'], [name='sort']"
               class="form-control"/>
    </div>
    <div class="col-md-3">
        <label class="form-label">Sort</label>
        <select name="sort" class="form-select"
                th:hx-get="@{/entities}"
                hx-target="#table-content"
                hx-push-url="true"
                hx-include="[name='filter'], [name='showSkipped']">
            <option value="name,ASC">Name A-Z</option>
            <option value="name,DESC">Name Z-A</option>
            <option value="updatedAt,DESC">Newest</option>
        </select>
    </div>
    <div class="col-md-3">
        <div class="form-check form-switch mt-4">
            <input class="form-check-input" type="checkbox" id="showSkippedToggle"
                   name="showSkipped" th:checked="${showSkipped}"
                   th:hx-get="@{/entities(showSkipped=${!showSkipped})}"
                   hx-target="#table-content"
                   hx-push-url="true"
                   hx-include="[name='filter'], [name='sort']">
            <label class="form-check-label" for="showSkippedToggle">Show skipped</label>
        </div>
    </div>
</div>
```

## 4) Suggested Shared Files To Copy Across Apps

- `templates/fragments/utils.html`
  - `pagination()`
  - `sorting(...)`
  - `searchFilter(...)`
- `templates/<feature>/fragments/inlineEdit.html`
  - display/edit/persist fragments
  - JS handlers for Enter/Escape/blur

## 5) Rollout Checklist Across Apps

- [ ] Add rules block to each app's agent guidance doc.
- [ ] Standardize one shared pagination/sorting fragment per app.
- [ ] Standardize one shared inline-edit fragment per app.
- [ ] Convert one representative list page first.
- [ ] Verify URL state persistence for filter/sort/page.
- [ ] Verify lazy-loading safety for every rendered relation.
