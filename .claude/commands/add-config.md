---
description: Add or modify application configuration
---

Add/modify configuration: **{{description}}**

**Target:** {{target}} (e.g., "application.yml", "application-dev.yml", "application-production.yml")
**Type:** {{type}} (e.g., "crawl config", "database", "security", "batch job", "custom properties")

**Workflow:**
1. **Review current config:**
   - Read specified application.yml file
   - Check CLAUDE.md for planned config structure
   - Look for related @ConfigurationProperties classes

2. **Design config:**
   - Follow YAML best practices
   - Use existing patterns in the file
   - Reference planned structure if available

3. **Implement:**
   - Add/modify YAML configuration
   - Create/update @ConfigurationProperties class if needed:
     ```kotlin
     @ConfigurationProperties(prefix = "{{prefix}}")
     data class {{Name}}Properties(
         val property: Type = defaultValue
     )
     ```
   - Register in Spring Boot if needed

4. **Validate:**
   - Run application to test config loads: `./gradlew bootRun`
   - Check for binding errors in logs
   - Verify properties are accessible in code

5. **Document:**
   - Show what was added/changed
   - Explain configuration options
   - Provide example usage in code

**Common configurations:**
- **Crawl config**: See CLAUDE.md lines for planned structure
- **Batch jobs**: `spring.batch.job.name`, scheduling cron
- **Database**: Connection pool, JPA settings
- **Security**: User credentials, CSRF settings
- **Custom**: Domain-specific settings with @ConfigurationProperties

**After configuration:**
- Test that app starts successfully
- Show example of accessing config in code
- Offer to commit
