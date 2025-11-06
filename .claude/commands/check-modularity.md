---
description: Verify Spring Modulith module boundaries and dependencies
---

Run modularity verification and analyze the results:

**Steps:**
1. Run ModularityTest: `./gradlew test --tests ModularityTest`
2. Check for violations:
   - Circular dependencies
   - Unauthorized module access
   - Package visibility issues
3. If documentation was generated, summarize build/spring-modulith-docs/
4. Show current module structure and dependencies

**Report:**
- ✅ All modules valid
- ❌ Any violations with specific files/packages
- Module dependency graph summary
- Recommendations for fixing violations

**If violations found:**
- Explain the issue
- Suggest fixes:
  - Move code to appropriate module
  - Use events instead of direct calls
  - Add to allowedDependencies if appropriate
  - Make packages internal

**Reference:** See CLAUDE.md "Modularity Guidelines" section for patterns.
