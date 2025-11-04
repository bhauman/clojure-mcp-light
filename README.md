# clojure-mcp-light

> **⚠️ Experimental**: This project is in early stages. We're experimenting with what works to bring better Clojure support to Claude Code.

Automatic delimiter fixing for Clojure files in Claude Code using hooks and parinfer.

## Overview

Clojure-mcp-light provides automatic detection and fixing of delimiter errors (mismatched brackets, parentheses, braces) when working with Clojure files in Claude Code. The hook system intercepts file operations and transparently fixes delimiter issues before they cause problems.

## Features

- **Automatic delimiter detection** using edamame parser
- **Auto-fixing with parinfer-rust** for intelligent delimiter repair
- **Write operations**: Detects and fixes delimiter errors before writing files
- **Edit operations**: Creates backup before edits, auto-fixes after, or restores from backup if unfixable
- **Real-time feedback**: Communicates fixes and issues back to Claude Code via hook responses

## Requirements

- [Babashka](https://github.com/babashka/babashka) - Fast-starting Clojure scripting environment
- [parinfer-rust](https://github.com/eraserhd/parinfer-rust) - Delimiter inference and fixing
- [Claude Code](https://docs.claude.com/en/docs/claude-code) - The Claude CLI tool

## Setup

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

3. Make the hook script executable:
   ```bash
   chmod +x clj-paren-repair-hook.bb
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

## Using with ClojureMCP

This project works well with [ClojureMCP](https://github.com/bhauman/clojure-mcp) for REPL integration. You can configure ClojureMCP to only enable the eval tool, avoiding conflicts with the delimiter fixing hooks.

Create a `.clojure-mcp/config.edn` file in your project:

```clojure
{:enabled-tools [:clojure_eval]}
```

This ensures that ClojureMCP only provides the REPL evaluation functionality while the hooks handle delimiter fixing.

See [settings_example/clojure-mcp-config.edn](settings_example/clojure-mcp-config.edn) for an example configuration.

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

## Future Plans

This is an experimental project exploring Clojure tooling integration with Claude Code. Potential areas for expansion:

- Additional Clojure-aware tools and linting
- Integration with clojure-lsp
- REPL integration and evaluation support
- More sophisticated code formatting

## Contributing

Since this is experimental, contributions and ideas are welcome! Feel free to:

- Open issues with suggestions or bug reports
- Submit PRs with improvements
- Share your experiments and what works (or doesn't work)

## License

Eclipse Public License - v 2.0 (EPL-2.0)

See [LICENSE.md](LICENSE.md) for the full license text.
