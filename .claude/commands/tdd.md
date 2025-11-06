---
description: Test-driven development cycle for new functionality
---

Implement **{{functionality}}** using TDD approach.

**TDD Cycle:**
1. **RED**: Write failing tests first
   - Happy path test cases
   - Edge cases: {{edge_cases}} (e.g., null, empty, invalid input, multiple results)
   - Error cases: {{error_cases}} (e.g., not found, duplicate, constraint violation)

2. **GREEN**: Implement minimal code to pass tests
   - Follow existing patterns from similar functionality
   - Add proper validation and error handling

3. **REFACTOR**: Clean up code
   - Extract common logic
   - Improve naming
   - Remove duplication

**Test Type:** {{test_type}} (e.g., "unit with mocks", "integration with Testcontainers", "both")

**Process:**
1. Show me the test cases you'll write
2. Write tests and run them (should fail)
3. Implement the functionality
4. Run tests again (should pass)
5. Ask if I want integration tests added
6. Show coverage summary
7. Offer to commit

Make sure tests follow project conventions:
- Use AssertJ for assertions
- MockitoExtension for unit tests
- @DataJpaTest for repository tests
- @SpringBootTest for integration tests
