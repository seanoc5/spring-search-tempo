---
description: Refactor code with approval workflow
---

Refactor: **{{description}}**

**Constraints:**
- Keep public API: {{keep_api}} (e.g., "identical", "compatible", "can break")
- Test coverage: {{coverage}} (e.g., "maintain current", "improve", "add missing")
- Approval needed: {{approval}} (e.g., "show plan first", "proceed directly")

**Refactoring workflow:**
1. **Analyze**: Understand current implementation
   - Read relevant code
   - Identify code smells or improvement opportunities

2. **Plan**: Create refactoring plan
   - What will change (classes, methods, structure)
   - What will stay the same (public API, behavior)
   - Files affected count

3. **Approve**: Show you the plan with code snippets
   - Wait for your confirmation if requested

4. **Execute**: Perform refactoring
   - Make changes incrementally
   - Run tests after each major change

5. **Verify**: Ensure nothing broke
   - Run all affected tests
   - Run ModularityTest
   - Check for compilation errors

6. **Review**: Show summary of changes
   - Files modified
   - Lines added/removed
   - Test results

**Common refactorings:**
- Extract interface/class
- Move method to appropriate service
- Consolidate duplicate code
- Rename for clarity
- Simplify complex conditionals
- Extract configuration to properties

**Safety checks:**
- All tests must pass
- No new modularity violations
- Public API unchanged (unless approved)
- Code follows Kotlin idioms
