# clojure-mcp-light

> This is not an MCP.

Simple CLI tools for LLM coding assistants working with Clojure.

**TL;DR:** Three CLI tools for Clojure development with LLM coding assistants:
- [`clj-nrepl-eval`](#clj-nrepl-eval-llm-nrepl-connection-without-an-mcp) - nREPL evaluation from command line
- [`clj-paren-repair-claude-hook`](#clj-paren-repair-claude-hook) - Auto-fix delimiters via hooks (Claude Code)
- [`clj-paren-repair`](#clj-paren-repair) - On-demand delimiter fix (Gemini CLI, Codex, etc.)

## The Problem

LLMs produce delimiter errors when editing Clojure code - mismatched parentheses, brackets, and braces. This leads to the **"Paren Edit Death Loop"** where the AI repeatedly fails to fix delimiter errors, wasting tokens and blocking progress.

Secondary problem: LLM coding assistants need to connect to a stateful Clojure REPL for evaluation.

These tools solve both problems.

## Quick Reference

| Tool | Use Case |
|------|----------|
| [`clj-nrepl-eval`](#clj-nrepl-eval-llm-nrepl-connection-without-an-mcp) | REPL evaluation from any LLM |
| [`clj-paren-repair-claude-hook`](#clj-paren-repair-claude-hook) | Claude Code (or any LLM that supports Claude hooks) |
| [`clj-paren-repair`](#clj-paren-repair) | Gemini CLI, Codex CLI, any LLM with shell |

## Quick Install

**Install hook tool:**
```bash
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2
```
**Note:** The hook will not work unless configured in `~/.claude/settings.json` - see configuration section below.

**Install nREPL eval tool:**
```bash
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
```

**Install on-demand repair tool:**
```bash
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'
```

See individual tool sections below for important configuration and usage details.

## Requirements

- [Babashka](https://github.com/babashka/babashka) - Fast Clojure scripting (includes cljfmt)
  - **Note:** Version 1.12.212 or later is required when working with Codex and other tools that sandbox bash execution
- [bbin](https://github.com/babashka/bbin) - Babashka package manager

**Optional:**
- [parinfer-rust](https://github.com/eraserhd/parinfer-rust) - Faster delimiter repair when available

---

## `clj-nrepl-eval` LLM nREPL connection without an MCP

nREPL client for evaluating Clojure code from the command line. 

This provides coding assistants access to REPL eval via shell
calls. It is specifically designed for LLM interaction and allows
the LLM to discover and manage its REPL sessions without needing
to configure an MCP server.

### How it helps

- Lets LLMs evaluate code in a running REPL
- Maintains persistent sessions per target
- Auto-discovers nREPL ports
- Auto-repairs delimiters before evaluation
- Helpful output that guides LLMs

### Installation

Installation is in two steps, installing the command line tool and
then telling the coding assistants about `clj-nrepl-eval`.

```bash
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
```

Or from local checkout:
```bash
bbin install . --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
```

Verify that it is installed and working by starting a nREPL and then doing a test eval.

```bash
# this missing paren is there to demonstrate that delimiters are repaired automatically
clj-nrepl-eval -p 7888 "(+ 1 2 3" 
# => 6
```

### Telling the LLM about `clj-nrepl-eval`

Choose one or more of these approaches. Each has trade-offs:

| Approach | Availability | When info is used | Best for |
|----------|--------------|-------------------|----------|
| Custom instructions | All LLM clients | Always in context | Simplest, most effective |
| Slash commands | Most coding assistants | When you invoke it | On-demand awareness |
| Skills | Claude Code only | LLM pulls when needed | Automatic, context-aware |

Each can be installed **locally** (per-project) or **globally** (all projects).

#### Custom instructions

The simplest and perhaps most effective approach. Works with all LLM coding assistants.

Add to your custom instructions file:
- **Global**: `~/.claude/CLAUDE.md`, `~/.gemini/GEMINI.md`, `~/.codex/AGENTS.md`
- **Local**: `./CLAUDE.md`, `./GEMINI.md`, `./AGENTS.md` in project root

```markdown
# Clojure REPL Evaluation

The command `clj-nrepl-eval` is installed on your path for evaluating Clojure code via nREPL.

**Discover nREPL servers:**

`clj-nrepl-eval --discover-ports`

**Evaluate code:**

`clj-nrepl-eval -p <port> "<clojure-code>"`

With timeout (milliseconds)

`clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"`

The REPL session persists between evaluations - namespaces and state are maintained.
Always use `:reload` when requiring namespaces to pick up changes.
```

#### Slash commands

Lets you interject REPL awareness into the conversation when you need it. Available in most coding assistants (installation varies by client).

- **/start-nrepl** - Starts an nREPL server in the background and reports the port
- **/clojure-nrepl** - Provides detailed usage info for `clj-nrepl-eval`

**Claude Code** - uses `.md` files in `commands/` directory:

```bash
# Global: ~/.claude/commands/
# Local: .claude/commands/
mkdir -p ~/.claude/commands
cp commands/*.md ~/.claude/commands/
```

**Gemini CLI** - uses `.toml` files ([docs](https://google-gemini.github.io/gemini-cli/docs/cli/custom-commands.html)):

```bash
# Global: ~/.gemini/commands/
# Local: .gemini/commands/
```

**Codex CLI** - uses `.md` files ([docs](https://developers.openai.com/codex/guides/slash-commands)):

```bash
# Global: ~/.codex/prompts/
```

#### Skills

Allows the LLM to pull in REPL information when it's actually needed. Currently Claude Code only.

```bash
# Global (all projects)
mkdir -p ~/.claude/skills
cp -r skills/clojure-eval ~/.claude/skills/

# Local (this project only)
mkdir -p .claude/skills
cp -r skills/clojure-eval .claude/skills/
```

### Usage Tips

**Easiest strategy:** Start nREPL before your coding session

Start an nREPL before asking the LLM to use one. This way it can
simply discover the port of the server in the current project
with `--discover-ports`. Minimal ceremony to start interacting with the REPL.

**Advanced:** Have the LLM start and manage your nREPL sessions

Claude and other LLMs are perfectly capable of starting your nREPL
server in the background and reading the port from the output. They
can also kill the server if it gets hung on a bad eval.

### Customize to your workflow

Once you start working with `clj-nrepl-eval` inside a coding assistant,
it will quickly become clear how to adjust the above prompts to fit
your specific projects and workflow.


---

## clj-paren-repair-claude-hook

[Claude Code Hooks](https://code.claude.com/docs/en/hooks) let you run
shell commands before or after Claude's tool calls. This hook
intercepts Write/Edit operations and automatically fixes delimiter
errors before they hit the filesystem.

> In my usage these Hooks have fixed 100% of the errors detected.

**Note:** This binary already works with [Codex](https://developers.openai.com/codex/hooks) (which shares Claude Code's hook wire format — see [Use with Codex](#use-with-codex-apply_patch)) and with [Antigravity](https://antigravity.google/docs/hooks) (via an adapter — see [Use with Antigravity](#use-with-antigravity)). As other LLM clients add hook support they'll be covered too; for example, when Gemini CLI adds hooks it can use the same approach.

**Why hooks instead of MCP tools?**

With MCP-based editing tools, you lose Claude Code's native UI
integration — tool calls are poorly formatted and difficult to
read. Hooks let Claude Code operate normally with its native
Edit/Write tools, preserving the clean diff UI you're used to, while
transparently fixing delimiter errors behind the scenes.

### How it helps

- Fixes errors transparently before they're written to disk
- Uses **zero tokens** - happens outside LLM invocation
- Preserves Claude Code's native diff UI and tool integration
- Install once globally, works on all Clojure file edits

### Installation

```bash
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2
```

Or from local checkout:
```bash
bbin install .
```

### Configuration

Add to `~/.claude/settings.json`:

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

### Options

- `--cljfmt` - Enable automatic code formatting with cljfmt
- `--stats` - Enable statistics tracking (logs to `~/.clojure-mcp-light/stats.log`)
- `--log-level LEVEL` - Set log level (trace, debug, info, warn, error)
- `--log-file PATH` - Path to log file (default: `./.clojure-mcp-light-hooks.log`)
- `-h, --help` - Show help message

### How It Works

- **PreToolUse hooks** run before Write/Edit operations, fixing content before it's written
- **PostToolUse hooks** run after Edit operations, fixing any issues introduced
- **SessionEnd hook** cleans up temporary files when Claude Code sessions end

**Write operations**: If delimiter errors are detected, the content is fixed via parinfer before writing. If unfixable, the write is blocked.

**Edit operations**: A backup is created before the edit. After the edit, if delimiter errors exist, they're fixed automatically. If unfixable, the file is restored from backup.

### Use with Codex (`apply_patch`)

[Codex](https://developers.openai.com/codex/hooks) supports the same hook wire
format as Claude Code, so the **same `clj-paren-repair-claude-hook` binary**
works there too — no separate tool to install. The one difference: Codex makes
all file edits through a single `apply_patch` tool (a patch document) rather
than `Write`/`Edit`. The hook understands `apply_patch` and repairs every
Clojure file a patch touches.

Add to `~/.codex/hooks.json` (global) or `<repo>/.codex/hooks.json` (per
project):

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": ".*",
        "hooks": [
          { "type": "command", "command": "clj-paren-repair-claude-hook --cljfmt" }
        ]
      }
    ],
    "PreToolUse": [
      {
        "matcher": "^apply_patch$",
        "hooks": [
          { "type": "command", "command": "clj-paren-repair-claude-hook --cljfmt" }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "^apply_patch$",
        "hooks": [
          { "type": "command", "command": "clj-paren-repair-claude-hook --cljfmt" }
        ]
      }
    ]
  }
}
```

**What each hook does:**

- **PreToolUse `apply_patch`** backs up the files the patch will touch.
- **PostToolUse `apply_patch`** repairs delimiter errors in the patched files. If a file can't be fixed it's restored from the backup and Codex is told why. On a *successful* repair Codex is notified that the file changed on disk (re-read before further edits) — `apply_patch` matches on context lines, so a silent change can desync the next patch.
- **SessionStart** sweeps `clojure-mcp-light` temp dirs idle for 3+ days. Codex has no `SessionEnd` event, so cleanup runs at session start instead.

**Codex-specific notes:**

- **Trust the hooks.** Codex requires you to review and trust command hooks before they run — use `/hooks` in the TUI. Trust is keyed to the file's hash, so editing `hooks.json` requires re-trusting.
- **Hooks run only in the interactive TUI**, not under `codex exec`.
- **`SessionStart` fires when the session is configured** — i.e. after your first message, not at bare launch.
- The `^apply_patch$` matcher scopes the hook to file edits. (`.*` also works but then the hook runs on every tool call, including shell commands.)

### Use with Antigravity

[Antigravity](https://antigravity.google/docs/hooks) (the `agy` CLI) also
supports command hooks, and the **same binary** handles it — but its wire
format differs from Claude Code / Codex (camelCase fields, no event name in the
payload, and a PostToolUse that must return `{}`). So Antigravity mode is opted
into explicitly with `--antigravity` and the event is passed via `--event`,
since the payload doesn't carry one.

Antigravity edits files through `write_to_file` / `replace_file_content` /
`multi_replace_file_content` (the target path is in `args.TargetFile`). Hooks
live in `.agents/hooks.json` (workspace) or `~/.gemini/config/hooks.json`
(global), and the schema nests events under a named hook:

```json
{
  "clj-delimiter-repair": {
    "PreToolUse": [
      {
        "matcher": "write_to_file|replace_file_content|multi_replace_file_content",
        "hooks": [
          { "type": "command", "command": "clj-paren-repair-claude-hook --antigravity --event PreToolUse --cljfmt" }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "write_to_file|replace_file_content|multi_replace_file_content",
        "hooks": [
          { "type": "command", "command": "clj-paren-repair-claude-hook --antigravity --event PostToolUse --cljfmt" }
        ]
      }
    ],
    "Stop": [
      { "type": "command", "command": "clj-paren-repair-claude-hook --antigravity --event Stop --cljfmt" }
    ]
  }
}
```

**What each hook does:**

- **PostToolUse** does the work: it repairs the edited file on disk (the path comes from the PostToolUse `toolCall.args.TargetFile`). Files that can't be repaired are left exactly as the model wrote them — **no backup, no revert**. (PostToolUse output must be `{}`, so there's no way to message the agent; silently reverting under it would be more confusing than a leftover error.)
- **PreToolUse** is a safety net: it stashes the target path keyed by `stepIdx` in case a future Antigravity version stops including `toolCall` in PostToolUse (the documented contract). It returns `{"decision":"allow"}` and never blocks.
- **Stop** runs the stale temp-dir sweep — Antigravity has no `SessionStart`/`SessionEnd`.

**Antigravity-specific notes:**

- **Shell-bypass gap (important here).** These hooks only fire for the file-edit *tools*. `agy` will sometimes write a file via `run_command` (e.g. a `cat <<EOF` heredoc), which carries no `TargetFile` and so isn't caught. To cover that, pair with [`clj-paren-repair`](#clj-paren-repair) or instruct the agent to prefer its edit tools over shell writes.
- **Repairs are silent.** PostToolUse returns `{}`, so the hook can't tell the agent it changed the file. In practice `agy` re-reads files after editing, so this rarely matters.
- **`--event` is required** in Antigravity mode — the payload has no event-name field, so the hook is told which event it's handling via the flag.

### Statistics Tracking

Statistics tracking helps validate that these tools are working well.
At some point Clojurists may not need them—either because models stop
producing delimiter errors, or because assistants include parinfer
internally. Tracking helps us know when that day comes.

Add `--stats` to track delimiter events:

```bash
clj-paren-repair-claude-hook --cljfmt --stats
```

Stats are written to `~/.clojure-mcp-light/stats.log` as EDN:

```clojure
{:event-type :delimiter-error, :hook-event "PreToolUse", :timestamp "2025-11-09T14:23:45.123Z", :file-path "/path/to/file.clj"}
{:event-type :delimiter-fixed, :hook-event "PreToolUse", :timestamp "2025-11-09T14:23:45.234Z", :file-path "/path/to/file.clj"}
```

Use the included stats summary script:

```bash
./scripts/stats-summary.bb
```

Sample output:

```
clojure-mcp-light Utility Validation
============================================================

Delimiter Repair Metrics
========================
  Total Writes/Edits:           829
  Clean Code (no errors):       794  ( 95.8% of total)
  Errors Detected:               35  (  4.2% of total)
  Successfully Fixed:            35  (100.0% of errors)
  Failed to Fix:                  0  (  0.0% of errors)
  Parse Errors:                   0  (  0.0% of fix attempts)

```

### Logging

Enable logging for debugging:

```bash
# Debug level
clj-paren-repair-claude-hook --log-level debug --cljfmt

# Trace level (maximum verbosity)
clj-paren-repair-claude-hook --log-level trace --log-file ~/hook-debug.log
```

### Verify Installation

```bash
echo '{"hook_event_name":"PreToolUse","tool_name":"Write","tool_input":{"file_path":"test.clj","content":"(def x 1)"}}' | clj-paren-repair-claude-hook
```

**Verify hooks are running in Claude Code:**

After Claude Code edits a Clojure file, expand the tool output to see
hook messages. In the terminal, press `ctrl-r` (or click the edit) and
look for these messages surrounding the diff:

```
⎿ PreToolUse:Edit hook succeeded:
  ... edit diff ...
⎿ PostToolUse:Edit hook succeeded:
```

If you don't see these messages, check that your `~/.claude/settings.json`
hook configuration is correct.

**Test delimiter repair:**

Prompt Claude Code to intentionally write malformed Clojure (e.g., missing
closing paren) to verify the hook fixes it automatically.


### Pro tip

Combine with `clj-paren-repair` for complete coverage - hooks handle Edit/Write tools, but LLMs can also edit via Bash (sed, awk). Having both tools catches all cases.

---

## clj-paren-repair

A shell command for LLM coding assistants that don't support hooks
(like Gemini CLI and Codex CLI). When the LLM encounters a delimiter
error, it calls this tool to fix it instead of trying to repair it manually.

**The key insight:** When we observe an AI in the "Paren Edit Death Loop"—repeatedly
failing to fix delimiter errors—we're witnessing a desperate search for a solution.
`clj-paren-repair` provides an escape route that short-circuits this behavior.

**Why this works:** Modern SOTA models produce very accurate edits with only
small delimiter discrepancies. The errors are minor enough that parinfer
can reliably fix them. This simple solution works surprisingly well.

**Hooks vs clj-paren-repair:** Hooks are the clear winner when available—they
use zero tokens and happen without LLM invocation. However, `clj-paren-repair`
works universally with any LLM that has shell access. When Gemini CLI gets
hooks support, we should use them. Until then, `clj-paren-repair` is sufficient.

**Using both together:** Even with hooks configured, having `clj-paren-repair`
available provides complete coverage. Hooks handle Edit/Write tools, but LLMs
can also edit files via Bash (sed, awk, etc.). Having both tools catches all cases.

### How it helps

- Provides escape route from "Paren Edit Death Loop"
- LLM calls it when it encounters delimiter errors
- Works with **any** LLM that has shell access
- Automatically formats files with cljfmt

### Installation

```bash
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'
```

Or from local checkout:
```bash
bbin install . --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'
```

### Usage

```bash
clj-paren-repair path/to/file.clj
clj-paren-repair src/core.clj src/util.clj test/core_test.clj
clj-paren-repair --help
```

### Setup: Custom Instructions

Add to your global or local custom instructions file
(`GEMINI.md`, `AGENTS.md`, `CLAUDE.md` etc.):

```markdown
# Clojure Parenthesis Repair

The command `clj-paren-repair` is installed on your path.

Examples:
`clj-paren-repair <files>`
`clj-paren-repair path/to/file1.clj path/to/file2.clj path/to/file3.clj`

**IMPORTANT:** Do NOT try to manually repair parenthesis errors.
If you encounter unbalanced delimiters, run `clj-paren-repair` on the file
instead of attempting to fix them yourself. If the tool doesn't work,
report to the user that they need to fix the delimiter error manually.

The tool automatically formats files with cljfmt when it processes them.
```

---

## Using Multiple Tools Together

**Best practice for Claude Code users:**
1. Configure hooks for automatic fixing (zero tokens)
2. Also have `clj-paren-repair` available for Bash-based edits
3. Use `clj-nrepl-eval` for REPL evaluation

**For other LLM clients (Gemini CLI, Codex, etc.):**
1. Install `clj-paren-repair` and add custom instructions
2. Use `clj-nrepl-eval` for REPL evaluation

---

## What These Tools Solve (and Don't)

**Problem A: Bad delimiters in output** - SOLVED

These tools fix mismatched/missing parentheses in edit results.

**Problem B: old_string matching failures** - NOT SOLVED

Sometimes LLMs struggle to produce an `old_string` that exactly matches the file content, causing edit failures. This is less common with newer models.

For full solution to Problem B: [ClojureMCP](https://github.com/bhauman/clojure-mcp) sexp-editing tools.

---

## Why Not Just Use ClojureMCP?

[ClojureMCP](https://github.com/bhauman/clojure-mcp) provides comprehensive Clojure tooling, but:

- ClojureMCP tools aren't native to the client - no diff UI, no integrated output formatting
- ClojureMCP duplicates/conflicts with tools the client already has
- These CLI tools work *with* the client's native tools instead of replacing them

You can use both together. Configure ClojureMCP to expose only `:clojure_eval` if desired:

```clojure
;; .clojure-mcp/config.edn
{:enable-tools [:clojure_eval]
 :enable-prompts []
 :enable-resources []}
```

You can also use ClojureMCP's prompts, resources, and agents
functionality to create a suite of tools that work across LLM clients.

---

## Contributing

Contributions and ideas are welcome! Feel free to:

- Open issues with suggestions or bug reports
- Submit PRs with improvements
- Share your experiments and what works (or doesn't work)

## License

Eclipse Public License - v 2.0 (EPL-2.0)

See [LICENSE.md](LICENSE.md) for the full license text.
