# clojure-mcp-light

> **⚠️ Experimental**: This project is in early stages. We're experimenting with what works to bring better Clojure support to Claude Code.

Automatic delimiter fixing for Clojure files in Claude Code using hooks and parinfer, plus a standalone nREPL evaluation tool.

## Philosophy

This project explores **minimal tooling** for Clojure development with Claude Code. Rather than building a comprehensive coding assistant, we're testing whether smart parinfer application combined with REPL evaluation is sufficient for productive Clojure development.

**Why minimal tooling?**

- Claude Code may be fine-tuned to use its own built-in tools effectively
- Simpler tooling is easier to maintain and understand
- Potentially supports Claude Code Web (which doesn't support MCP servers)
- If minimal tools are sufficient, that's valuable for the Clojure community to know
- Less complexity means fewer moving parts and potential issues

**How is this different from clojure-mcp?**

[ClojureMCP](https://github.com/bhauman/clojure-mcp) is a full coding assistant (minus the LLM loop) with comprehensive Clojure tooling. This project takes the opposite approach: find the minimum viable tooling needed to get decent Clojure support while leveraging Claude Code's native capabilities.

If this minimal approach proves sufficient, it demonstrates that Clojure developers can achieve good results with just:
- Smart delimiter fixing (parinfer)
- REPL evaluation
- Claude Code's built-in tools

## Overview

Clojure-mcp-light provides two main tools:

1. **Automatic delimiter fixing hooks** (`clojure-mcp-light-hook`) - Detects and fixes delimiter errors (mismatched brackets, parentheses, braces) when working with Clojure files in Claude Code. The hook system intercepts file operations and transparently fixes delimiter issues before they cause problems.

2. **nREPL evaluation tool** (`clojure-mcp-light`) - A command-line tool for evaluating Clojure code via nREPL with automatic delimiter repair, timeout handling, and formatted output.

## Features

- **Automatic delimiter detection** using edamame parser
- **Auto-fixing with parinfer-rust** for intelligent delimiter repair
- **Write operations**: Detects and fixes delimiter errors before writing files
- **Edit operations**: Creates backup before edits, auto-fixes after, or restores from backup if unfixable
- **Real-time feedback**: Communicates fixes and issues back to Claude Code via hook responses

## Requirements

- [Babashka](https://github.com/babashka/babashka) - Fast-starting Clojure scripting environment
- [bbin](https://github.com/babashka/bbin) - Babashka package manager (recommended for installation)
- [parinfer-rust](https://github.com/eraserhd/parinfer-rust) - Delimiter inference and fixing
- [Claude Code](https://docs.claude.com/en/docs/claude-code) - The Claude CLI tool

## Installation

### Recommended: Install via bbin

The easiest way to install clojure-mcp-light is using [bbin](https://github.com/babashka/bbin), the Babashka package manager.

1. Install bbin if you haven't already:
   ```bash
   brew install bbin  # macOS
   # or
   bash < <(curl -s https://raw.githubusercontent.com/babashka/bbin/main/bbin)
   ```

2. Install clojure-mcp-light:
   ```bash
   # From GitHub (once published)
   bbin install io.github.yourusername/clojure-mcp-light

   # Or from local checkout
   git clone <repo-url> clojure-mcp-light
   cd clojure-mcp-light
   bbin install .
   ```

3. Install parinfer-rust (required dependency):
   ```bash
   # See https://github.com/eraserhd/parinfer-rust for installation
   # Must be available on your PATH
   ```

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
               "command": "/Users/yourname/.local/bin/clojure-mcp-light-hook"
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
               "command": "/Users/yourname/.local/bin/clojure-mcp-light-hook"
             }
           ]
         }
       ]
     }
   }
   ```

   Replace `/Users/yourname/.local/bin/` with your actual bbin installation directory (run `bbin bin` to find it).

5. The `clojure-mcp-light` command is now available globally for nREPL evaluation:
   ```bash
   clojure-mcp-light "(+ 1 2 3)"
   ```

### Alternative: Manual Setup

1. Clone this repository:
   ```bash
   git clone <repo-url> clojure-mcp-light
   cd clojure-mcp-light
   ```

2. Install dependencies:

   **Babashka** (macOS):
   ```bash
   brew install babashka/brew/babashka
   ```

   **parinfer-rust**:

   https://github.com/eraserhd/parinfer-rust

   > I had to compile parinfer rust for Apple Silicon and install it manually

   The `parinfer-rust` binary must be on Claude Code's PATH. To check Claude Code's PATH, you can ask Claude to run:
   ```bash
   echo $PATH
   ```

   Install the binary to a directory in the PATH (e.g., `/usr/local/bin`):
   ```bash
   # Example: create symbolic link in /usr/local/bin
   sudo ln -s /path/to/downloaded/parinfer-rust /usr/local/bin/parinfer-rust
   ```

3. Make the scripts executable:
   ```bash
   chmod +x clj-paren-repair-hook.bb
   chmod +x clojure-nrepl-eval.bb
   ```

   Optionally, add the `clojure-nrepl-eval.bb` script to your PATH so it can be used from anywhere:
   ```bash
   # Example: create symbolic link in /usr/local/bin
   sudo ln -s $(pwd)/clojure-nrepl-eval.bb /usr/local/bin/clojure-nrepl-eval
   ```

   Or ensure the directory containing the script is on Claude Code's PATH. To check Claude Code's PATH:
   ```bash
   echo $PATH
   ```

4. Configure Claude Code hooks by adding to `.claude/settings.local.json`:
   ```json
   {
     "hooks": {
       "PreToolUse": [
         {
           "matcher": "Write|Edit",
           "hooks": [
             {
               "type": "command",
               "command": "/absolute/path/to/clj-paren-repair-hook.bb"
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
               "command": "/absolute/path/to/clj-paren-repair-hook.bb"
             }
           ]
         }
       ]
     }
   }
   ```

   See [settings_example/settings.local.json](settings_example/settings.local.json) for a complete example.

## Slash Commands

This project includes custom slash commands for Claude Code to streamline your Clojure workflow:

### Available Commands

- **/start-nrepl** - Automatically starts an nREPL server in the background, detects the port, and creates a `.nrepl-port` file
- **/clojure-eval** - Provides information about using `clojure-nrepl-eval.bb` for REPL-driven development

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

## clojure-mcp-light - nREPL Evaluation Tool

The main command-line tool for evaluating Clojure code via nREPL with automatic delimiter repair.

When installed via bbin, the command is available globally as `clojure-mcp-light`. For manual installation, use the `clojure-nrepl-eval.bb` script directly.

### Features

- **Direct nREPL communication** using bencode protocol
- **Automatic delimiter repair** before evaluation using parinfer-rust
- **Timeout and interrupt handling** for long-running evaluations
- **Formatted output** with dividers between results
- **Flexible configuration** via command-line flags, environment variables, or `.nrepl-port` file

### Usage

**With bbin installation:**

```bash
# Evaluate code (port auto-detected from .nrepl-port file or NREPL_PORT env)
clojure-mcp-light "(+ 1 2 3)"

# Specify port explicitly
clojure-mcp-light --port 7888 "(println \"Hello\")"

# Use short flags
clojure-mcp-light -p 7889 "(* 5 6)"

# Set timeout (in milliseconds)
clojure-mcp-light --timeout 5000 "(Thread/sleep 10000)"

# Show help
clojure-mcp-light --help
```

**With manual installation:**

```bash
# Same options, but use the script directly
./clojure-nrepl-eval.bb "(+ 1 2 3)"
./clojure-nrepl-eval.bb --port 7888 "(println \"Hello\")"
```

### Automatic Delimiter Repair

The tool automatically fixes missing or mismatched delimiters before evaluation:

```bash
# This will be auto-fixed from "(+ 1 2 3" to "(+ 1 2 3)"
clojure-mcp-light "(+ 1 2 3"
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

The script needs to connect to an nREPL server. There are three ways to configure the port, checked in this order:

1. **Command-line flag** (highest priority)
   ```bash
   ./clojure-nrepl-eval.bb --port 7888 "(+ 1 2)"
   ```

2. **Environment variable**
   ```bash
   export NREPL_PORT=7888
   ./clojure-nrepl-eval.bb "(+ 1 2)"
   ```

3. **`.nrepl-port` file** (lowest priority)

   Most Clojure REPLs automatically create a `.nrepl-port` file in your project directory when they start. The script will automatically read this file:
   ```bash
   # Start your REPL (creates .nrepl-port automatically)
   clj -M:repl/nrepl

   # In another terminal, the script auto-detects the port
   ./clojure-nrepl-eval.bb "(+ 1 2)"
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
