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

But the main reason you may want to try this approach is to have
**clean code diffs presented by Claude Code when a file write or edit
takes place**.

Since this relies on hooks that hook into the default Claude Code
editing tools the UI is unaffected by integrating this into your
Claude Code setup.

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

- **Automatic delimiter detection** using edamame parser
- **Auto-fixing with parinfer-rust** for intelligent delimiter repair
- **Write operations**: Detects and fixes delimiter errors before writing files
- **Edit operations**: Creates backup before edits, auto-fixes after, or restores from backup if unfixable
- **Real-time feedback**: Communicates fixes and issues back to Claude Code via hook responses

## Requirements

- [Babashka](https://github.com/babashka/babashka) - Fast-starting Clojure scripting environment
- [bbin](https://github.com/babashka/bbin) - Babashka package manager
- [parinfer-rust](https://github.com/eraserhd/parinfer-rust) - Delimiter inference and fixing
- [Claude Code](https://docs.claude.com/en/docs/claude-code) - The Claude CLI tool

## Installation

### Install via bbin

1. Install bbin if you haven't already:
   
   See https://github.com/babashka/bbin for more details.

2. Install parinfer-rust (required dependency):
   ```bash
   # See https://github.com/eraserhd/parinfer-rust for installation
   # The parinfer-rust binary must be available on your PATH
   ```

3. Install clojure-mcp-light:
   ```bash
   # From GitHub
   bbin install https://github.com/bhauman/clojure-mcp-light.git
   bbin install https://github.com/bhauman/clojure-mcp-light.git --as clj-nrepl-eval --main-opts '["-m"  "clojure-mcp-light.nrepl-eval"]'

   # Or from local checkout
   bbin install .
   bbin install . --as clj-nrepl-eval --main-opts '["-m"  "clojure-mcp-light.nrepl-eval"]'
   ```

   This installs both commands:
   - `clj-paren-repair-claude-hook` - Hook for automatic delimiter fixing
   - `clj-nrepl-eval` - nREPL evaluation tool

4. Configure Claude Code hooks in your project's `.claude/settings.local.json`:
   ```json
   {
     "hooks": {
       "PreToolUse": [
         {
           "matcher": "Write|Edit",
           "hooks": [
             {
               "type": "command",
               "command": "clj-paren-repair-claude-hook"
             }
           ]
         }
       ],
       "PostToolUse": [
         {
           "matcher": "Edit",
           "hooks": [
             {
               "type": "command",
               "command": "clj-paren-repair-claude-hook"
             }
           ]
         }
       ]
     }
   }
   ```

   See [settings_example/settings.local.json](settings_example/settings.local.json) for a complete example.

5. Verify installation:
   ```bash
   # Test nREPL evaluation (requires running nREPL server)
   clj-nrepl-eval --port 7888 "(+ 1 2 3)"

   # Test hook manually
   echo '{"hook_event_name":"PreToolUse","tool_name":"Write","tool_input":{"file_path":"test.clj","content":"(def x 1)"}}' | clj-paren-repair-claude-hook
   ```

## Legacy Scripts

For users who prefer not to use bbin, standalone Babashka scripts are available:
- `clj-paren-repair-hook.bb` - Hook script
- `clojure-nrepl-eval.bb` - nREPL evaluation script

These can be run directly with `bb` or `./script-name.bb` after making them executable. However, **bbin installation is strongly recommended** for easier updates and global access to commands.

## Slash Commands

This project includes custom slash commands for Claude Code to streamline your Clojure workflow:

### Available Commands

- **/start-nrepl** - Automatically starts an nREPL server in the background, detects the port, and creates a `.nrepl-port` file
- **/clojure-eval** - Provides information about using `clj-nrepl-eval` for REPL-driven development

### Setup

Copy or symlink the command files to your project's `.claude/commands/` directory:

```bash
# Create the commands directory if it doesn't exist
mkdir -p .claude/commands

# Copy commands
cp commands/*.md .claude/commands/

# Or create symlinks (recommended - stays in sync with updates)
ln -s $(pwd)/commands/clojure-eval.md .claude/commands/clojure-eval.md
ln -s $(pwd)/commands/start-nrepl.md .claude/commands/start-nrepl.md
```

### Usage

Once set up, you can use these commands in Claude Code conversations:

```
/start-nrepl
```

This will start an nREPL server and set up the `.nrepl-port` file automatically.

```
/clojure-eval
```

This provides Claude with context about REPL evaluation, making it easier to work with your running Clojure environment.

## clj-nrepl-eval - nREPL Evaluation Tool

The main command-line tool for evaluating Clojure code via nREPL with automatic delimiter repair.

### Features

- **Direct nREPL communication** using bencode protocol
- **Automatic delimiter repair** before evaluation using parinfer-rust
- **Timeout and interrupt handling** for long-running evaluations
- **Formatted output** with dividers between results
- **Flexible configuration** via command-line flags, environment variables, or `.nrepl-port` file

### Usage

```bash
# Evaluate code (port auto-detected from .nrepl-port file or NREPL_PORT env)
clj-nrepl-eval "(+ 1 2 3)"

# Specify port explicitly
clj-nrepl-eval --port 7888 "(println \"Hello\")"

# Use short flags
clj-nrepl-eval -p 7889 "(* 5 6)"

# Set timeout (in milliseconds)
clj-nrepl-eval --timeout 5000 "(Thread/sleep 10000)"

# Show help
clj-nrepl-eval --help
```

### Automatic Delimiter Repair

The tool automatically fixes missing or mismatched delimiters before evaluation:

```bash
# This will be auto-fixed from "(+ 1 2 3" to "(+ 1 2 3)"
clj-nrepl-eval "(+ 1 2 3"
# => 6
```

### Options

- `-p, --port PORT` - nREPL port (default: from .nrepl-port or NREPL_PORT env)
- `-H, --host HOST` - nREPL host (default: 127.0.0.1 or NREPL_HOST env)
- `-t, --timeout MILLISECONDS` - Timeout in milliseconds (default: 120000)
- `-h, --help` - Show help message

### Environment Variables

- `NREPL_PORT` - Default nREPL port
- `NREPL_HOST` - Default nREPL host

### Port Configuration

The command needs to connect to an nREPL server. There are three ways to configure the port, checked in this order:

1. **Command-line flag** (highest priority)
   ```bash
   clj-nrepl-eval --port 7888 "(+ 1 2)"
   ```

2. **Environment variable**
   ```bash
   export NREPL_PORT=7888
   clj-nrepl-eval "(+ 1 2)"
   ```

3. **`.nrepl-port` file** (lowest priority)

   Most Clojure REPLs automatically create a `.nrepl-port` file in your project directory when they start. The command will automatically read this file:
   ```bash
   # Start your REPL (creates .nrepl-port automatically)
   clj -M:repl/nrepl

   # In another terminal, auto-detects the port
   clj-nrepl-eval "(+ 1 2)"
   ```

   You can also create this file manually:
   ```bash
   echo "7888" > .nrepl-port
   ```

**Starting an nREPL Server**

If you don't have an nREPL server running, you need to start one first. Here are common ways:

```bash
# Using Clojure CLI with nREPL dependency
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.0.0"}}}' -M -m nrepl.cmdline

# Using Leiningen
lein repl :headless

# Using Babashka
bb nrepl-server 7888
```

Each of these will start an nREPL server and typically create a `.nrepl-port` file that the script can use.

**Error Handling**

If the script cannot find a port through any of the three methods, it will exit with an error:
```
Error: No nREPL port found
Provide port via --port, NREPL_PORT env var, or .nrepl-port file
```

Make sure you have an nREPL server running and the port is configured using one of the methods above.

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

## Contributing

Since this is experimental, contributions and ideas are welcome! Feel free to:

- Open issues with suggestions or bug reports
- Submit PRs with improvements
- Share your experiments and what works (or doesn't work)

## License

Eclipse Public License - v 2.0 (EPL-2.0)

See [LICENSE.md](LICENSE.md) for the full license text.
