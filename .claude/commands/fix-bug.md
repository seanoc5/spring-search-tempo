---
description: Investigate and fix a bug with systematic approach
---

Fix bug: **{{description}}**

**Context provided:**
- Error message/stack trace: {{error}}
- When it occurs: {{when}}
- Recent changes: {{changes}}

**Investigation workflow:**
1. **Reproduce**: Run test or command to see the error
2. **Locate**: Find the source code causing the issue
   - Search error message in codebase
   - Check related classes/methods
3. **Understand**: Read the code and identify root cause
4. **Fix**: Implement solution following project patterns
5. **Verify**: Run tests to confirm fix
6. **Prevent**: Add test to catch regression

**Common issues to check:**
- LazyInitializationException → Add @Transactional or JOIN FETCH
- NullPointerException → Add null checks or validation
- ConstraintViolation → Check entity validation rules
- Circular dependency → Review module boundaries
- Port already in use → Check running processes
- Database connection → Verify Docker container running

**After fix:**
- Run affected tests
- Run ModularityTest if changed dependencies
- Show me what was changed and why
- Offer to commit with descriptive message

**Reference:** CLAUDE.md "Troubleshooting" section for common fixes.
