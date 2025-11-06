---
description: Run tests for a specific Spring Modulith module
---

Run tests for the **{{module}}** module.

**Module:** {{module}} (e.g., base, batch, web)

**Commands to run:**
```bash
# All tests in module
./gradlew test --tests "com.oconeco.springsearchtempo.{{module}}.*"

# Specific test type (optional)
# Repository tests: ./gradlew test --tests "*{{module}}*Repository*"
# Service tests: ./gradlew test --tests "*{{module}}*Service*"
# Integration tests: ./gradlew test --tests "*{{module}}*Integration*"
```

**Report:**
1. Number of tests run
2. Pass/fail summary
3. Any failures with details
4. Execution time
5. Coverage estimate if available

**If failures:**
- Show stack trace
- Identify likely cause
- Offer to investigate and fix
