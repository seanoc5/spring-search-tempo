---
description: Commit changes and create pull request
---

Commit current work and create PR.

**Scope:** {{scope}} (e.g., "all changes", "only entity and repo", "specific files")
**Target branch:** {{target}} (default: master)

**Git workflow:**
1. **Review changes:**
   ```bash
   git status
   git diff
   git log -3 --oneline
   ```

2. **Stage files:**
   - If scope specified, stage only those files
   - Otherwise stage all relevant changes
   - Exclude unrelated changes

3. **Commit:**
   - Analyze changes to draft commit message
   - Follow repo style (check recent commits)
   - Include:
     - What changed (feature/fix/refactor)
     - Why it changed
     - Key implementation details
     - Generated with Claude Code footer

4. **Prepare PR:**
   - Review all commits since branch diverged from target
   - Check if branch needs push: `git status`
   - Push with `-u` if needed

5. **Create PR:**
   ```bash
   gh pr create --title "..." --body "..."
   ```

   **PR body format:**
   ```markdown
   ## Summary
   - [Key changes as bullets]

   ## Test plan
   - [ ] Tests pass: ./gradlew test
   - [ ] Modularity check: ./gradlew test --tests ModularityTest
   - [ ] Manual testing: [specific steps]

   🤖 Generated with Claude Code
   ```

6. **Return PR URL** for review

**Commit message format:**
```
Brief summary (50 chars or less)

Detailed explanation of changes:
- What was added/changed/fixed
- Why these changes were needed
- Any important implementation details

🤖 Generated with Claude Code

Co-Authored-By: Claude <noreply@anthropic.com>
```
