# 🪝 Hooks Setup Guide for Spring Search Tempo

This guide helps you set up automated hooks to streamline your workflow with Claude Code.

## 📋 Quick Start

### Option 1: Copy-Paste Configuration (Easiest)

1. **Open your settings file**:
   ```bash
   nano ~/.claude/settings.json
   ```

2. **Add this configuration** (merge with existing hooks if any):
   ```json
   {
     "hooks": {
       "PreToolUse": [
         {
           "matcher": "Bash",
           "hooks": [
             {
               "type": "command",
               "comment": "Compile before git commits",
               "command": "jq -r '.tool_input.command' | { read cmd; if echo \"$cmd\" | grep -q 'git commit'; then echo 'Compiling Kotlin...'; cd /opt/work/springboot/spring-search-tempo && ./gradlew compileKotlin -q || exit 2; fi; }"
             }
           ]
         }
       ],
       "PostToolUse": [
         {
           "matcher": "Edit|Write",
           "hooks": [
             {
               "type": "command",
               "comment": "Auto-compile after Kotlin file edits",
               "command": "jq -r '.tool_input.file_path' | { read file; if echo \"$file\" | grep -q '\\.kt$'; then echo 'Compiling...'; cd /opt/work/springboot/spring-search-tempo && timeout 10 ./gradlew compileKotlin -q 2>&1 | tail -5; fi; }"
             }
           ]
         }
       ]
     }
   }
   ```

3. **Save and exit** (Ctrl+X, Y, Enter in nano)

### Option 2: Use Claude Code Commands

You can also manage hooks using built-in commands (if available):
```
/hooks
```

## 🎯 Recommended Hooks for This Project

### 1. **Pre-Commit Compilation Check** ✅
**Event**: `PreToolUse` on `Bash` (git commit)
**Purpose**: Ensures Kotlin compiles before committing
**Benefit**: Catches errors before they hit version control

**What it does**:
- Detects `git commit` commands
- Runs `./gradlew compileKotlin`
- **Blocks commit if compilation fails** (exit code 2)

### 2. **Auto-Compile After Edits** 🔄
**Event**: `PostToolUse` on `Edit|Write` (.kt files)
**Purpose**: Immediate feedback on Kotlin syntax errors
**Benefit**: Know immediately if your edit broke something

**What it does**:
- Detects edits to `.kt` files
- Runs quick compilation check
- Shows last 5 lines of output (errors if any)
- Has 10-second timeout to avoid hanging

### 3. **Protect Sensitive Files** 🔒
**Event**: `PreToolUse` on `Edit|Write`
**Purpose**: Prevents accidental modification of production configs
**Benefit**: Safety guardrail for critical files

**What it does**:
- Blocks edits to: `application-production.yml`, `.env`, files with "credentials" or "secrets"
- Returns exit code 2 to block the tool use

**Configuration**:
```json
{
  "PreToolUse": [
    {
      "matcher": "Edit|Write",
      "hooks": [
        {
          "type": "command",
          "command": "python3 -c \"import json, sys; data=json.load(sys.stdin); path=data.get('tool_input',{}).get('file_path',''); sys.exit(2 if any(p in path for p in ['application-production.yml', '.env', 'credentials', 'secrets']) else 0)\""
        }
      ]
    }
  ]
}
```

### 4. **Database Stats Reminder** 📊
**Event**: `PostToolUse` on `Bash` (bootRun)
**Purpose**: Reminds you to check results after batch jobs
**Benefit**: Never forget to verify crawl results

**What it does**:
- Detects when bootRun completes
- Suggests running `/db-query` to see stats

### 5. **Slash Command Suggestions** 💡
**Event**: `UserPromptSubmit`
**Purpose**: Contextual hints about available slash commands
**Benefit**: Learn and remember your custom commands

**What it does**:
- Detects keywords like "bug", "error", "entity", "test"
- Suggests relevant slash commands (`/fix-bug`, `/add-entity`, `/tdd`)

**Configuration**:
```json
{
  "UserPromptSubmit": [
    {
      "matcher": "",
      "hooks": [
        {
          "type": "command",
          "command": "jq -r '.prompt // \"\"' | { read prompt; case \"$prompt\" in *bug*|*error*) echo 'Tip: /fix-bug';; *entity*) echo 'Tip: /add-entity';; *test*) echo 'Tip: /tdd or /test-module';; esac; exit 0; }"
        }
      ]
    }
  ]
}
```

### 6. **Cleanup on Stop** 🧹
**Event**: `Stop`
**Purpose**: Kills lingering background Gradle processes
**Benefit**: Prevents port conflicts and resource leaks

**What it does**:
- Runs `pkill -f 'gradlew.*bootRun'` when Claude stops responding
- Harmless if no processes running

## 🔍 Testing Your Hooks

After setup, test each hook:

### Test Pre-Commit Hook:
```bash
# Make a small change
echo "// test" >> src/main/kotlin/com/oconeco/spring_search_tempo/SpringSearchTempoApplication.kt

# Try to commit (should trigger compilation)
git add .
git commit -m "test"
# You should see "Compiling Kotlin..." message
```

### Test Auto-Compile Hook:
```bash
# Edit a Kotlin file through Claude
# You should see "Compiling..." message after the edit
```

### Test Sensitive File Protection:
```bash
# Try to edit application-production.yml through Claude
# Should be blocked with an error message
```

## 🛠️ Customization

### Change Project Path
If your project is not at `/opt/work/springboot/spring-search-tempo`, update:
```bash
# In hooks, replace this:
cd /opt/work/springboot/spring-search-tempo

# With your actual path:
cd /your/actual/path
```

### Adjust Compilation Timeout
Default is 10 seconds. Change in auto-compile hook:
```bash
timeout 10 ./gradlew compileKotlin
# Change 10 to desired seconds
```

### Add More Protected Files
In sensitive file protection, add to the list:
```python
['application-production.yml', '.env', 'credentials', 'secrets', 'YOUR_FILE']
```

## 🐛 Troubleshooting

### Hook Not Running
1. Check `~/.claude/settings.json` syntax (valid JSON?)
2. Run `/hooks` to see registered hooks
3. Check tool names match exactly (`Bash`, not `bash`)

### Compilation Takes Too Long
- Increase timeout: `timeout 30 ./gradlew compileKotlin`
- Or disable: Remove the hook from settings

### Hook Blocks Everything
- If a PreToolUse hook has bugs, it might block all tool uses
- **Fix**: Edit `~/.claude/settings.json` and remove the problematic hook
- Or change `exit 2` to `exit 0` temporarily

### Permission Denied
```bash
chmod +x ~/.claude/settings.json
```

## 📚 Advanced Hooks Ideas

### Run Tests on Commit
```json
{
  "command": "jq -r '.tool_input.command' | { read cmd; if echo \"$cmd\" | grep -q 'git commit'; then cd /opt/work/springboot/spring-search-tempo && ./gradlew test --tests ModularityTest || exit 2; fi; }"
}
```

### Auto-Format Kotlin Files
(Requires ktlint)
```json
{
  "command": "jq -r '.tool_input.file_path' | { read file; if echo \"$file\" | grep -q '\\.kt$'; then cd /opt/work/springboot/spring-search-tempo && ./gradlew ktlintFormat; fi; }"
}
```

### Desktop Notifications
(Linux with notify-send)
```json
{
  "hooks": {
    "Notification": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "notify-send 'Claude Code' 'Awaiting input'"
          }
        ]
      }
    ]
  }
}
```

## ⚠️ Security Considerations

**From Claude Code Docs**:
> "You must consider the security implication of hooks as you add them, because hooks run automatically during the agent loop with your current environment's credentials."

**Best Practices**:
- ✅ Review all hook code before adding
- ✅ Use specific matchers (not `*`) where possible
- ✅ Avoid storing credentials in hooks
- ✅ Test in isolated environment first
- ✅ Use restrictive file permissions: `chmod 600 ~/.claude/settings.json`
- ❌ Don't run untrusted scripts in hooks
- ❌ Don't expose sensitive data in hook output

## 📖 Learn More

- Hooks Documentation: https://code.claude.com/docs/en/hooks-guide.md
- Settings Documentation: https://code.claude.com/docs/en/settings.md
- Slash Commands: See `.claude/commands/README.md` in this project

## 🎓 Next Steps

After setting up hooks:
1. ✅ Test each hook works as expected
2. ✅ Customize paths and timeouts for your environment
3. ✅ Add project-specific hooks as needed
4. ✅ Review hook behavior periodically

---

**Created**: 2025-11-06
**Project**: Spring Search Tempo
**For**: LLM-guided development with Claude Code
