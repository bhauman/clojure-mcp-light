# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

clojure-mcp-light is NOT an MCP server. It provides minimal CLI tooling for Clojure development in Claude Code through two main tools:

1. **clj-paren-repair-claude-hook** - Hook for automatic delimiter fixing in Clojure files
2. **clj-nrepl-eval** - nREPL evaluation tool with automatic delimiter repair

The project leverages Claude Code's native hook system to transparently fix delimiter errors (mismatched brackets, parentheses, braces) without custom MCP tooling.

## Development Commands

### Running Tests
```bash
bb test
```
This runs all tests in the test/ directory using Babashka's built-in test runner.

### Installing Locally with bbin
```bash
# Install both commands
bbin install .
bbin install . --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
```

### Testing the Hook Manually

Basic hook test without flags:
```bash
echo '{"hook_event_name":"PreToolUse","tool_name":"Write","tool_input":{"file_path":"test.clj","content":"(def x 1)"}}' | bb -m clojure-mcp-light.hook
```

Test hook with --cljfmt flag (requires cljfmt installed):
```bash
echo '{"hook_event_name":"PostToolUse","tool_name":"Write","tool_input":{"file_path":"test.clj","content":"(def x 1)"},"tool_response":"success"}' | bb -m clojure-mcp-light.hook -- --cljfmt
```

Test with stats tracking enabled:
```bash
echo '{"hook_event_name":"PreToolUse","tool_name":"Write","tool_input":{"file_path":"test.clj","content":"(def x 1)"}}' | bb -m clojure-mcp-light.hook -- --stats
# Check stats log:
cat ~/.clojure-mcp-light/stats.log
```

Test with logging enabled:
```bash
CML_ENABLE_LOGGING=true echo '{"hook_event_name":"PreToolUse","tool_name":"Write","tool_input":{"file_path":"test.clj","content":"(def x 1)"}}' | bb -m clojure-mcp-light.hook -- --cljfmt
```

Show help:
```bash
bb -m clojure-mcp-light.hook -- --help
```

Note: After installing with bbin, you can use `clj-paren-repair-claude-hook` instead of `bb -m clojure-mcp-light.hook`.

### Testing with Claude Code Integration

To test the hooks with actual Write and Edit operations in Claude Code:

1. **Update settings** to use the hook with desired flags in `.claude/settings.local.json`:
```json
{
  "hooks": {
    "PreToolUse": [{
      "matcher": "Write|Edit|Bash",
      "hooks": [{"type": "command", "command": "CML_ENABLE_LOGGING=true bb -m clojure-mcp-light.hook -- --cljfmt --stats"}]
    }],
    "PostToolUse": [{
      "matcher": "Edit|Write",
      "hooks": [{"type": "command", "command": "CML_ENABLE_LOGGING=true bb -m clojure-mcp-light.hook -- --cljfmt --stats"}]
    }],
    "SessionEnd": [{
      "hooks": [{"type": "command", "command": "CML_ENABLE_LOGGING=true bb -m clojure-mcp-light.hook -- --cljfmt"}]
    }]
  }
}
```

Note: The `--stats` flag enables tracking of delimiter events to `~/.clojure-mcp-light/stats.log`

Note: For production use with bbin-installed commands, replace `bb -m clojure-mcp-light.hook` with `clj-paren-repair-claude-hook`.

2. **Restart Claude Code** - Settings changes require a restart to take effect

3. **Test Write operations** - Ask Claude to create a poorly formatted file:
```clojure
;; Example poorly formatted code
(defn badly-formatted [x]
(let [y (* x 2)]
y))
```

4. **Verify formatting** - Check that the file was auto-formatted:
```clojure
;; Should be formatted to:
(defn badly-formatted [x]
  (let [y (* x 2)]
    y))
```

5. **Check logs** - View hook execution logs:
```bash
tail -50 .clojure-mcp-light-hooks.log
```

Look for log entries showing:
- `PostWrite:` or `PostEdit:` with file path
- `Running cljfmt fix on:` with file path
- `cljfmt succeeded` or `cljfmt failed`

6. **Test Edit operations** - Ask Claude to modify an existing Clojure file and verify the PostToolUse hook runs cljfmt

### Testing nREPL Evaluation
```bash
# Start an nREPL server first (creates .nrepl-port automatically)
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.0.0"}}}' -M -m nrepl.cmdline

# In another terminal, evaluate code
clj-nrepl-eval "(+ 1 2 3)"
```

### Troubleshooting Hook Tests

**Hook errors during testing:**
- Check that all required tools are on PATH (parinfer-rust, cljfmt if using --cljfmt)
- Verify the hook compiles: `bb -m clojure-mcp-light.hook -- --help`
- Check logs for detailed error messages: `tail -f .clojure-mcp-light-hooks.log`
- Test directly with echo: `echo '{}' | bb -m clojure-mcp-light.hook -- --cljfmt`

**cljfmt not running:**
- Ensure cljfmt is installed: `which cljfmt`
- Check that logging is enabled: `CML_ENABLE_LOGGING=true` in hook command
- Verify PostToolUse matcher includes both "Edit|Write"
- Check logs to confirm hook is being called: `grep "Running cljfmt" .clojure-mcp-light-hooks.log`
- Restart Claude Code after settings changes

**Changes not taking effect:**
- Always restart Claude Code after modifying `.claude/settings.local.json`
- For development, use `bb -m clojure-mcp-light.hook` to avoid needing bbin reinstalls
- For production, install with bbin and use `clj-paren-repair-claude-hook`
- Verify file extensions are processed: `.clj`, `.cljs`, `.cljc`, `.bb`, `.edn`

## Architecture

### Core Modules

**delimiter_repair.clj** - Core delimiter error detection and repair
- Uses edamame parser to detect delimiter errors specifically (not general syntax errors)
- Calls parinfer-rust CLI for intelligent delimiter repair
- Key functions: `delimiter-error?`, `fix-delimiters`

**hook.clj** - Claude Code hook implementation
- Intercepts Write and Edit tool operations via PreToolUse/PostToolUse hooks
- For Write: detects and fixes delimiter errors before writing, blocks write if unfixable
- For Edit: creates backup before edit, auto-fixes after edit, restores from backup if unfixable
- Optional `--cljfmt` flag enables automatic code formatting with cljfmt after write/edit operations
- CLI argument parsing via `tools.cli` with `handle-cli-args` function
- Uses multimethod dispatch on `[hook_event_name tool_name]` pairs
- Communicates fixes back to Claude via hook response format

**nrepl_eval.clj** - nREPL client with timeout and session management
- Direct bencode protocol implementation for nREPL communication
- Automatic delimiter repair before evaluation
- Timeout/interrupt handling for long-running evaluations
- **Persistent sessions**: Reuses session ID from per-target session files
- Session management: `--reset-session` flag to start fresh session
- Port detection: CLI flag > NREPL_PORT env > .nrepl-port file

**tmp.clj** - Unified temporary file management for Claude Code sessions
- Provides session-scoped temporary file paths with automatic cleanup
- Implements editor session detection with fallback strategies
- Creates deterministic paths based on user, hostname, session ID, and project
- Supports automatic cleanup via SessionEnd hook
- Key functions: `session-root`, `editor-scope-id`, `cleanup-session!`, `get-possible-session-ids`

### Temporary File Management Architecture

The `tmp` namespace provides a unified system for managing temporary files across all components (hooks, nREPL sessions) with automatic cleanup when Claude Code sessions end.

**Directory Structure:**
```
$XDG_RUNTIME_DIR (or java.io.tmpdir)
└── claude-code/
    └── {user-id}/
        └── {hostname}/
            └── {session-id}/           # or ppid-{pid}-{start-time}
                └── proj-{sha1(path)}/  # per-project isolation
                    ├── backups/        # Edit operation backups
                    └── nrepl/          # nREPL session files
```

**Session ID Strategy:**
1. Prefer `CML_CLAUDE_CODE_SESSION_ID` environment variable (set by PreToolUse Bash hook)
2. Fall back to parent process ID with start time: `ppid-{pid}-{start-time}`
3. Last resort: literal string `"global"`

This ensures stable session identification even when the environment variable isn't available, while maintaining isolation between concurrent Claude Code sessions.

**Project Isolation:**
Each project gets its own subdirectory identified by SHA-1 hash of the absolute project path. This prevents conflicts when working on multiple projects simultaneously.

**Automatic Cleanup:**
The SessionEnd hook automatically deletes session directories when Claude Code sessions terminate, preventing accumulation of temporary files. The cleanup process:
1. Attempts cleanup for all possible session IDs (both env-based and PPID-based)
2. Uses `babashka.fs/delete-tree` for recursive deletion
3. Logs cleanup operations (attempted, deleted, errors, skipped)
4. Never blocks SessionEnd events, even on errors

### Hook System Flow

**Write operations (PreToolUse):**
1. Detect delimiter errors in content
2. If errors found, attempt fix with parinfer
3. If fixable, update content and allow write with reason message
4. If unfixable, deny write with error message

**Edit operations (PreToolUse + PostToolUse):**
1. PreToolUse: Create backup of file in temp directory with session ID
2. Allow edit to proceed
3. PostToolUse: Check edited file for delimiter errors
4. If errors found, attempt fix
5. If fixable, apply fix and delete backup
6. If unfixable, restore from backup and block with error message
7. If no errors, delete backup silently

**Bash operations (PreToolUse):**
1. Prepend `CML_CLAUDE_CODE_SESSION_ID={session_id}` to command
2. This makes session ID available to child processes (e.g., nREPL eval)
3. Allow command to proceed with updated input

**SessionEnd operations:**
1. Extract session_id from hook input
2. Call `tmp/cleanup-session!` with session ID
3. Attempt cleanup for both env-based and PPID-based session IDs
4. Log cleanup results (attempted, deleted, errors, skipped)
5. Always return success, never block SessionEnd

### Session Persistence

The nREPL evaluation tool implements persistent sessions using the tmp namespace:
- Session files stored per target (host:port combination) in `{session-root}/nrepl/target-{host}-{port}.edn`
- Reused across invocations until nREPL server restarts or `--reset-session` used
- Allows stateful development: vars, namespaces, and loaded libraries persist
- Per-target isolation: each nREPL server gets its own session file
- Functions: `slurp-nrepl-target-session`, `spit-nrepl-target-session`, `delete-nrepl-target-session`

## Key Implementation Details

### Delimiter Detection vs General Errors
The `delimiter-error?` function is deliberately narrow - it only detects delimiter errors, not general syntax errors. This is by design to avoid false positives and unnecessary parinfer runs.

### File Type Detection
The hook only processes files ending in: `.clj`, `.cljs`, `.cljc`, `.bb`, `.edn`

### Logging
Hooks support optional logging via dynamic vars `*enable-logging*` and `*log-file*`. Disabled by default to avoid breaking hook protocol.

### nREPL Timeout Strategy
The `eval-expr-with-timeout` function polls every 250ms to honor timeout deadlines while reading responses. When timeout hits, it sends an nREPL `:interrupt` op to gracefully stop evaluation.

## Common Patterns

### When requiring namespaces via clj-nrepl-eval
Always use the `:reload` flag to ensure fresh code:
```bash
clj-nrepl-eval "(require 'my.namespace :reload)"
```

### Testing delimiter repair in isolation
```clojure
(require '[clojure-mcp-light.delimiter-repair :refer [delimiter-error? fix-delimiters]])
(delimiter-error? "(+ 1 2")  ; => true
(fix-delimiters "(+ 1 2")    ; => "(+ 1 2)"
```

### Using the tmp namespace for session-scoped files
```clojure
(require '[clojure-mcp-light.tmp :as tmp])

;; Get session root directory
(tmp/session-root {})
;; => "/tmp/claude-code/bruce/hostname/session-id/proj-abc123/"

;; Get backup directory (creates if doesn't exist)
(tmp/backups-dir {:session-id "my-session"})
;; => "/tmp/claude-code/bruce/hostname/my-session/proj-abc123/backups"

;; Get nREPL directory
(tmp/nrepl-dir {})
;; => "/tmp/claude-code/bruce/hostname/session-id/proj-abc123/nrepl"

;; Create a backup path for a file
(tmp/backup-path {} "/Users/bruce/project/src/core.clj")
;; => "/tmp/claude-code/bruce/hostname/session-id/proj-abc123/backups/Users/bruce/project/src/core.clj"

;; Get nREPL session file for a specific target
(tmp/nrepl-target-file {} {:host "localhost" :port 7888})
;; => "/tmp/claude-code/bruce/hostname/session-id/proj-abc123/nrepl/target-localhost-7888.edn"

;; Clean up session (typically called by SessionEnd hook)
(tmp/cleanup-session! {:session-id "my-session"})
;; => {:attempted ["my-session" "ppid-1234-..."]
;;     :deleted ["/tmp/claude-code/.../my-session"]
;;     :errors []
;;     :skipped ["/tmp/claude-code/.../ppid-1234-..."]}
```

### Hook response format
Hooks must return JSON matching Claude Code's hook protocol:
- PreToolUse: `{:hookSpecificOutput {:hookEventName "PreToolUse" :permissionDecision "allow"}}`
- PostToolUse: `{:hookSpecificOutput {:hookEventName "PostToolUse" :additionalContext "..."}}`
- SessionEnd: `{:hookSpecificOutput {:hookEventName "SessionEnd"}}`
- Block: `{:decision "block" :reason "..." :hookSpecificOutput {...}}`

### Statistics Tracking

Enable delimiter event tracking with the `--stats` flag to analyze LLM-generated code quality.

**Event types tracked:**
- `:delimiter-error` - Delimiter error detected in generated code
- `:delimiter-fixed` - Delimiter error successfully auto-fixed
- `:delimiter-fix-failed` - Delimiter error could not be auto-fixed
- `:delimiter-ok` - No delimiter errors (clean code)

**Log location:** `~/.clojure-mcp-light/stats.log`

**Format:** EDN entries, one per line:
```clojure
{:event-type :delimiter-error, :hook-event "PreToolUse", :timestamp "2025-11-09T14:23:45.123Z", :file-path "/Users/me/project/src/core.clj"}
{:event-type :delimiter-fixed, :hook-event "PreToolUse", :timestamp "2025-11-09T14:23:45.234Z", :file-path "/Users/me/project/src/core.clj"}
```

**Analyzing stats:**

Use the stats summary script:
```bash
./scripts/stats-summary.bb
```

This provides:
- Total event counts
- Breakdown by event type with percentages
- Breakdown by hook (PreToolUse:Write, PostToolUse:Edit)
- Top 10 files by event count
- Success metrics (fix rate, clean code rate)

Or use bb for custom queries:
```bash
# Count events by type
bb -e "(require '[clojure.edn :as edn]) \
  (->> (slurp \"$HOME/.clojure-mcp-light/stats.log\") \
       (clojure.string/split-lines) \
       (map edn/read-string) \
       (group-by :event-type) \
       (map (fn [[k v]] [k (count v)])) \
       (into {}))"

# Files with most errors
bb -e "(require '[clojure.edn :as edn]) \
  (->> (slurp \"$HOME/.clojure-mcp-light/stats.log\") \
       (clojure.string/split-lines) \
       (map edn/read-string) \
       (filter #(= :delimiter-error (:event-type %))) \
       (group-by :file-path) \
       (map (fn [[k v]] [k (count v)])) \
       (sort-by second >) \
       (take 5))"
```

## Environment Variables

### CML_ENABLE_LOGGING
- **Set by**: User in hook command or shell environment
- **Used by**: hook.clj (controls timbre logging)
- **Purpose**: Enable detailed logging of hook operations to file
- **Format**: String "true" to enable (e.g., `CML_ENABLE_LOGGING=true`)
- **Log file**: `.clojure-mcp-light-hooks.log` (relative to project root, configurable via --log-file)
- **Usage**: `CML_ENABLE_LOGGING=true bb -m clojure-mcp-light.hook -- --cljfmt`
- **Logs include**: CLI args, parsed options, hook events, cljfmt execution, errors

### CML_CLAUDE_CODE_SESSION_ID
- **Set by**: PreToolUse Bash hook (automatically prepended to all bash commands)
- **Used by**: tmp namespace (`editor-scope-id` function), nREPL eval, hook system
- **Purpose**: Provides stable session identifier for temporary file management
- **Format**: UUID string (e.g., `"c0414e38-637f-4782-9757-29fa0c0b86e4"`)
- **Lifecycle**: Set at session start by Claude Code, propagated to all child processes via Bash hook
- **Fallback**: If not available, uses PPID-based identifier (`ppid-{pid}-{start-time}`)

### XDG_RUNTIME_DIR
- **Set by**: User's system (XDG Base Directory specification)
- **Used by**: tmp namespace (`runtime-base-dir` function)
- **Purpose**: Preferred location for runtime temporary files
- **Format**: Absolute directory path (e.g., `"/run/user/1000"`)
- **Fallback**: If not set, uses `java.io.tmpdir` system property
- **Platform**: Primarily Linux; macOS and Windows typically use java.io.tmpdir fallback

### NREPL_PORT
- **Set by**: User (optional)
- **Used by**: nREPL eval tool (`get-port` function)
- **Purpose**: Override default nREPL port detection
- **Format**: Integer port number (e.g., `"7888"`)
- **Priority**: CLI flag > NREPL_PORT env var > `.nrepl-port` file
- **Example**: `export NREPL_PORT=7888`

## Dependencies

External tools required:
- **parinfer-rust** - Must be on PATH for delimiter repair
- **cljfmt** - (Optional) Must be on PATH for `--cljfmt` flag functionality
- **babashka** - For running as a script
- **bbin** - For installation

Clojure dependencies (bb.edn):
- borkdude/edamame - Parser for delimiter detection
- cheshire/cheshire - JSON encoding/decoding for hook protocol
- org.clojure/tools.cli - CLI argument parsing
- nrepl/bencode - nREPL protocol implementation

## Testing Philosophy

Tests live in `test/clojure_mcp_light/` mirroring source structure. Run with `bb test` which uses Babashka's test runner.
