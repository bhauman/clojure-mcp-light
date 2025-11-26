# clojure-mcp-light

> **This is NOT an MCP server.**

**TL;DR:**
- Two CLI tools: parinfer hook + nREPL evaluator
- Integrates via Claude Code hooks for clean diffs
- Minimal approach: just fix delimiters + REPL eval

The goal of this project is to provide a ClojureMCP-like experience to
Claude Code minimally with a couple of simple cli tools.

These tools together provide a better Clojure development experience
with Claude Code.

They help solve the two main problems:

* faulty delimiters in LLM output
* connecting to a stateful Clojure nREPL

But the main reason you may want to try this approach is to have an
unadulterated Claude Code experience that works with Clojure code.

Since this relies on hooks which work with the default Claude Code
editing tools the UI is unaffected by integrating this into your
Claude Code setup. I.e. you will be able to read and confirm code
diffs and you will no longer see poorly formatted clojure-mcp tool
calls.

> You can setup `clojure-mcp-light` once and then edit it any Clojure
> project with Claude Code with no additional configuration.

These scripts benefit from patterns developed and validated in ClojureMCP.

This can be used alongside ClojureMCP as there are no hard
incompatibilities, in fact, you can install these hooks at the root
level scope of your Claude Code config to allow you to edit Clojure
files without error.

> **⚠️ Experimental**: This project is in early stages. I am still
> using this heavily so that I can assess how well it works. Expect
> changes including a change to the name of the repo.

## Philosophy

This project explores **minimal tooling** for Clojure development with
Claude Code. Rather than using a comprehensive MCP server, we're
testing whether smart parinfer application combined with a robust REPL
evaluation cli script is sufficient for productive Clojure development.

**Why minimal tooling?**

- Claude Code may be fine-tuned to use its own built-in tools effectively
- Simpler tooling is easier to maintain and understand
- Potentially supports Claude Code Web (which doesn't support MCP servers)
- If minimal tools are sufficient, that's valuable for the Clojure community to know
- Less complexity means fewer moving parts and potential issues

**How is this different from clojure-mcp?**

[ClojureMCP](https://github.com/bhauman/clojure-mcp) is a full coding
assistant (minus the LLM loop) with comprehensive Clojure
tooling. This project takes the opposite approach: find the minimum
viable tooling needed to get decent Clojure support while leveraging
Claude Code's native capabilities.

If this minimal approach proves sufficient, it demonstrates that Clojure developers can achieve good results with just:
- Smart delimiter fixing (parinfer)
- REPL evaluation on the cli
- Claude Code's built-in tools

## Overview

Clojure-mcp-light provides two main tools:

1. **Automatic delimiter fixing hooks** (`clj-paren-repair-claude-hook`) - Detects and fixes delimiter errors (mismatched brackets, parentheses, braces) when working with Clojure files in Claude Code. The hook system intercepts file operations and transparently fixes delimiter issues before they cause problems.

2. **nREPL evaluation tool** (`clj-nrepl-eval`) - A command-line tool for evaluating Clojure code via nREPL with automatic delimiter repair, timeout handling, and formatted output.

## Features

- **Automatic delimiter error detection** using edamame parser
- **Intelligent delimiter repair** with automatic backend selection:
  - Prefers [parinfer-rust](https://github.com/eraserhd/parinfer-rust) when available (faster, battle-tested)
  - Falls back to [parinferish](https://github.com/oakmac/parinferish) (pure Clojure, no external dependencies)
- **Write operations**: Detects and fixes delimiter errors before writing files
- **Edit operations**: Creates backup before edits, auto-fixes after, or restores from backup if unfixable
- **Optional code formatting**: `--cljfmt` flag enables automatic code formatting with cljfmt after write/edit operations
- **Statistics tracking**: `--stats` flag enables event tracking for delimiter errors, fixes, and successes
- **Automatic cleanup**: SessionEnd hook removes temporary files when Claude Code sessions terminate
- **Session-scoped temp files**: Organized directory structure with per-project and per-session isolation
- **Real-time feedback**: Communicates fixes and issues back to Claude Code via hook responses

## Requirements

- [Babashka](https://github.com/babashka/babashka) - Fast-starting Clojure scripting environment (includes cljfmt)
- [bbin](https://github.com/babashka/bbin) - Babashka package manager
- [Claude Code](https://docs.claude.com/en/docs/claude-code) - The Claude CLI tool

**Optional:**
- [parinfer-rust](https://github.com/eraserhd/parinfer-rust) - Delimiter inference and fixing - clojure-mcp-light will use this when its available

## Installation

### Install via bbin

1. Install bbin if you haven't already:

   See https://github.com/babashka/bbin for more details.

2. Install clojure-mcp-light (run both commands):

   From GitHub:
   ```bash
   bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.0
   ```
   ```bash
   bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.0 --as clj-nrepl-eval --main-opts '["-m"  "clojure-mcp-light.nrepl-eval"]'
   ```

   Or from local checkout:
   ```bash
   bbin install .
   ```
   ```bash
   bbin install . --as clj-nrepl-eval --main-opts '["-m"  "clojure-mcp-light.nrepl-eval"]'
   ```

   This is to install both commands:
   - `clj-paren-repair-claude-hook` - Hook for automatic delimiter fixing
   - `clj-nrepl-eval` - nREPL evaluation tool

4. Configure Claude Code hooks in `~/.claude/settings.json`:
   ```json
   {
     "hooks": {
       "PreToolUse": [
         {
           "matcher": "Write|Edit",
           "hooks": [
             {
               "type": "command",
               "command": "clj-paren-repair-claude-hook --cljfmt"
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
               "command": "clj-paren-repair-claude-hook --cljfmt"
             }
           ]
         }
       ],
       "SessionEnd": [
         {
           "hooks": [
             {
               "type": "command",
               "command": "clj-paren-repair-claude-hook --cljfmt"
             }
           ]
         }
       ]
     }
   }
   ```

   **Configuration notes:**
   - The `--cljfmt` flag enables automatic code formatting (uses cljfmt bundled with babashka)
   - Add `--stats` to enable delimiter event tracking to `~/.clojure-mcp-light/stats.log`
   - Add `--log-level debug` (or `trace`) for debugging hook operations
   - Add `--log-file PATH` to customize log file location
   - Remove `--cljfmt` if you don't want automatic formatting
   - The SessionEnd hook automatically cleans up temporary files when Claude Code sessions terminate

5. Verify installation:
   ```bash
   # Test nREPL evaluation (requires running nREPL server on port 7888)
   clj-nrepl-eval -p 7888 "(+ 1 2 3)"

   # Test hook manually
   echo '{"hook_event_name":"PreToolUse","tool_name":"Write","tool_input":{"file_path":"test.clj","content":"(def x 1)"}}' | clj-paren-repair-claude-hook

   # Test hook with cljfmt flag
   clj-paren-repair-claude-hook --help
   ```

### Install via Nix

If you use Nix, you can add this package to your development environment:

1. Create a `shell.nix` in your project:

   ```nix
   { pkgs ? import <nixpkgs> {} }:

   let
     clojure-mcp-light = pkgs.callPackage (pkgs.fetchFromGitHub {
       owner = "bhauman";
       repo = "clojure-mcp-light";
       rev = "v0.1.1";
       sha256 = "120qc7xzqdrm70scvkd08lf1zf89v9fj4z7bqk00ngz33y86xagx";
     }) {};
   in
   pkgs.mkShell {
     buildInputs = [
       clojure-mcp-light
       pkgs.babashka
       pkgs.clj-kondo
     ];
   }
   ```

   Or use it locally from a checkout:

   ```nix
   { pkgs ? import <nixpkgs> {} }:

   let
     clojure-mcp-light = pkgs.callPackage ./path/to/clojure-mcp-light/package.nix {};
   in
   pkgs.mkShell {
     buildInputs = [
       clojure-mcp-light
       pkgs.babashka
       pkgs.clj-kondo
     ];
   }
   ```

2. Enter the Nix shell:
   ```bash
   nix-shell
   ```

   This will make both `clj-paren-repair-claude-hook` and `clj-nrepl-eval` available in your PATH, along with parinfer-rust as a dependency.

3. Configure Claude Code hooks as described in step 4 of the bbin installation above.

## Slash Commands

> Experimental

This project includes custom slash commands for Claude Code to streamline your Clojure workflow:

### Available Commands

- **/start-nrepl** - Automatically starts an nREPL server in the background, detects the port, and creates a `.nrepl-port` file
- **/clojure-nrepl** - Provides information about using `clj-nrepl-eval` for REPL-driven development

### Setup

Copy or symlink the command files to your project's `.claude/commands/` directory:

```bash
# Create the commands directory if it doesn't exist
mkdir -p .claude/commands

# Copy commands
cp commands/*.md .claude/commands/

# Or create symlinks (recommended - stays in sync with updates)
ln -s $(pwd)/commands/clojure-nrepl.md .claude/commands/clojure-nrepl.md
ln -s $(pwd)/commands/start-nrepl.md .claude/commands/start-nrepl.md
```

### Usage

Once set up, you can use these commands in Claude Code conversations:

```
/start-nrepl
```

This will start an nREPL server and set up the `.nrepl-port` file automatically.

```
/clojure-nrepl
```

This provides Claude with context about REPL evaluation, making it easier to work with your running Clojure environment.

## clj-paren-repair-claude-hook - Hook Tool

The hook command for automatic delimiter fixing with optional code formatting and logging.

### Features

- **Automatic delimiter detection** using edamame parser
- **Intelligent delimiter repair** with automatic backend selection (parinfer-rust when available, parinferish fallback)
- **Optional code formatting** with cljfmt
- **Configurable file logging** for debugging hook operations

### Usage

```bash
# Basic usage (silent, no logging)
clj-paren-repair-claude-hook

# With automatic code formatting
clj-paren-repair-claude-hook --cljfmt

# With debug logging to default location (./.clojure-mcp-light-hooks.log)
clj-paren-repair-claude-hook --log-level debug --cljfmt

# With trace logging to custom file
clj-paren-repair-claude-hook --log-level trace --log-file /tmp/hook-debug.log

# Show help
clj-paren-repair-claude-hook --help
```

### Options

- `--cljfmt` - Enable automatic code formatting with cljfmt after write/edit operations
- `--stats` - Enable statistics tracking for delimiter events (logs to `~/.clojure-mcp-light/stats.log`)
- `--log-level LEVEL` - Set log level for file logging (trace, debug, info, warn, error, fatal, report)
- `--log-file PATH` - Path to log file (default: `./.clojure-mcp-light-hooks.log`)
- `-h, --help` - Show help message

### Logging

By default, the hook runs silently with no logging. To enable logging for debugging:

```bash
# Debug level logging (recommended for troubleshooting)
clj-paren-repair-claude-hook --log-level debug

# Trace level logging (maximum verbosity)
clj-paren-repair-claude-hook --log-level trace

# Custom log file location
clj-paren-repair-claude-hook --log-level debug --log-file ~/hook-debug.log
```

Log files include timestamps, namespaces, line numbers, and structured output for easy debugging.

**Enabling logging in hooks:**

To enable logging when running as a Claude Code hook, add the `--log-level` flag to your hook command. For example:
```
clj-paren-repair-claude-hook --log-level debug --cljfmt
```

### Statistics Tracking

The `--stats` flag enables tracking of delimiter events to help analyze LLM-generated code quality. Add it to your hook command:
```
clj-paren-repair-claude-hook --cljfmt --stats
```

**Event types tracked:**
- `:delimiter-error` - Delimiter error detected in generated code
- `:delimiter-fixed` - Delimiter error successfully auto-fixed
- `:delimiter-fix-failed` - Delimiter error could not be auto-fixed
- `:delimiter-ok` - No delimiter errors (clean code)

**Log format:**

Stats are written to `~/.clojure-mcp-light/stats.log` as EDN entries:

```clojure
{:event-type :delimiter-error, :hook-event "PreToolUse", :timestamp "2025-11-09T14:23:45.123Z", :file-path "/Users/me/project/src/core.clj"}
{:event-type :delimiter-fixed, :hook-event "PreToolUse", :timestamp "2025-11-09T14:23:45.234Z", :file-path "/Users/me/project/src/core.clj"}
{:event-type :delimiter-ok, :hook-event "PostToolUse", :timestamp "2025-11-09T14:25:10.456Z", :file-path "/Users/me/project/src/util.clj"}
```

**Analyzing stats:**

Use the included stats summary script for a quick overview:

```bash
# Show comprehensive stats summary
./scripts/stats-summary.bb

# Example output:
# Delimiter Event Statistics
# ============================================================
#
# Total Events: 42
#
# Events by Type
# ==============
#   delimiter-ok               28  ( 66.7%)
#   delimiter-error             8  ( 19.0%)
#   delimiter-fixed             5  ( 11.9%)
#   delimiter-fix-failed        1  (  2.4%)
#
# Events by Hook
# ==============
#   PreToolUse:Write           30
#   PostToolUse:Edit           12
#
# Top 10 Files by Event Count
# ===========================
#       8  src/core.clj
#       6  src/util.clj
#       4  test/core_test.clj
#
# Success Metrics
# ===============
#   Clean Code (no errors):        28
#   Errors Detected:                8
#   Successfully Fixed:             5
#   Failed to Fix:                  1
#
#   Fix Success Rate:           62.5%
#   Clean Code Rate:            77.8%
```

Or use Babashka for custom analysis:

```bash
# Count total events
cat ~/.clojure-mcp-light/stats.log | wc -l

# Filter by event type
bb -e "(require '[clojure.edn :as edn]) \
  (->> (slurp \"$HOME/.clojure-mcp-light/stats.log\") \
       (clojure.string/split-lines) \
       (map edn/read-string) \
       (filter #(= :delimiter-error (:event-type %))) \
       (count))"

# Group by event type
bb -e "(require '[clojure.edn :as edn]) \
  (->> (slurp \"$HOME/.clojure-mcp-light/stats.log\") \
       (clojure.string/split-lines) \
       (map edn/read-string) \
       (group-by :event-type) \
       (map (fn [[k v]] [k (count v)])) \
       (into {}))"

# Find files with most delimiter errors
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

## clj-nrepl-eval - nREPL Evaluation Tool

The main command-line tool for evaluating Clojure code via nREPL with automatic delimiter repair.

### Features

- **Direct nREPL communication** using bencode protocol
- **Automatic delimiter repair** before evaluation (uses parinfer-rust when available, parinferish fallback)
- **Timeout and interrupt handling** for long-running evaluations
- **Formatted output** with dividers between results
- **Server discovery** via `--discover-ports` flag (finds nREPL servers in current directory)
- **Connection tracking** via `--connected-ports` flag (shows previously connected sessions)
- **Persistent sessions** with per-target session management

### Usage

```bash
# Discover nREPL servers in current directory
clj-nrepl-eval --discover-ports

# Check previously connected sessions
clj-nrepl-eval --connected-ports

# Evaluate code (port required)
clj-nrepl-eval -p 7888 "(+ 1 2 3)"

# Specify port explicitly
clj-nrepl-eval --port 7888 "(println \"Hello\")"

# Set timeout (in milliseconds)
clj-nrepl-eval -p 7888 --timeout 5000 "(Thread/sleep 10000)"

# Reset session
clj-nrepl-eval -p 7888 --reset-session

# Show help
clj-nrepl-eval --help
```

### Automatic Delimiter Repair

The tool automatically fixes missing or mismatched delimiters before evaluation:

```bash
# This will be auto-fixed from "(+ 1 2 3" to "(+ 1 2 3)"
clj-nrepl-eval -p 7888 "(+ 1 2 3"
# => 6
```

### Options

- `-p, --port PORT` - nREPL port (required)
- `-H, --host HOST` - nREPL host (default: 127.0.0.1)
- `-t, --timeout MILLISECONDS` - Timeout in milliseconds (default: 120000)
- `-r, --reset-session` - Reset the persistent nREPL session
- `-c, --connected-ports` - List previously connected nREPL sessions
- `-d, --discover-ports` - Discover nREPL servers in current directory
- `-h, --help` - Show help message

### Workflow

**1. Start an nREPL server**

First, you need to start an nREPL server. Here are common ways:

```bash
# Using Clojure CLI with nREPL dependency
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.0.0"}}}' -M -m nrepl.cmdline

# Using Leiningen
lein repl :headless

# Using Babashka
bb nrepl-server 7888
```

The server will print its port when it starts, or you can check the `.nrepl-port` file if one was created.

**2. Discover available nREPL servers**

Use `--discover-ports` to find nREPL servers running in the current directory:

```bash
clj-nrepl-eval --discover-ports
# Discovered nREPL servers in current directory (/path/to/project):
#   localhost:7889 (bb)
#   localhost:55077 (clj)
#
# Total: 2 servers in current directory
```

Or use `--connected-ports` to see which servers you've previously connected to:

```bash
clj-nrepl-eval --connected-ports
# Active nREPL connections:
#   127.0.0.1:7888 (session: abc123...)
#   127.0.0.1:7889 (session: xyz789...)
#
# Total: 2 active connections
```

**3. Evaluate code**

Use the `-p` or `--port` flag to specify which server to use:

```bash
clj-nrepl-eval -p 7888 "(+ 1 2 3)"
# => 6
```

**Error Handling**

If you don't specify a port, you'll see:
```
Error: --port is required
Use --connected-ports to see available connections
```

## How It Works

The system uses Claude Code's hook mechanism to intercept file operations:

- **PreToolUse hooks** run before Write/Edit operations, allowing inspection and modification of content
- **PostToolUse hooks** run after Edit operations, enabling post-processing and restoration if needed
- **Detection → Fix → Feedback** flow ensures Claude is informed about what happened

**Write operations**: If delimiter errors are detected, the content is fixed via parinfer before writing. If unfixable, the write is blocked.

**Edit operations**: A backup is created before the edit. After the edit, if delimiter errors exist, they're fixed automatically. If unfixable, the file is restored from backup and Claude is notified.

## Example

Before (with delimiter error):
```clojure
(defn broken [x]
  (let [result (* x 2]
    result))
```

After (automatically fixed):
```clojure
(defn broken [x]
  (let [result (* x 2)]
    result))
```

The missing `)` is automatically added by parinfer, and Claude receives feedback about the fix.

## Using with ClojureMCP

These integrations don't conflict with a
[ClojureMCP](https://github.com/bhauman/clojure-mcp) integration. In
fact, if you would rather have ClojureMCP handle your Clojure
evaluation needs you can setup ClojureMCP to only expose its
`:clojure_eval` tool.

In your project directory place a `.clojure-mcp/config.edn` file:

```clojure
{:enable-tools [:clojure_eval]}
```

You can also hide the default ClojureMCP prompts and resources if you
don't want them availble in Claude Code:

```clojure
{:enable-tools [:clojure_eval]
 :enable-prompts []
 :enable-resources []}
```

## Contributing

Since this is experimental, contributions and ideas are welcome! Feel free to:

- Open issues with suggestions or bug reports
- Submit PRs with improvements
- Share your experiments and what works (or doesn't work)

## License

Eclipse Public License - v 2.0 (EPL-2.0)

See [LICENSE.md](LICENSE.md) for the full license text.
