# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.2-alpha] - 2025-11-08

### Added
- **Enhanced ClojureScript support** - Learning to use edamame to detect delimiter errors across the widest possible set of Clojure/ClojureScript files
  - Added `:features #{:clj :cljs :cljr :default}` to enable platform-specific reader features
  - Explicit readers for common ClojureScript/EDN tagged literals:
    - `#js` - JavaScript object literals
    - `#jsx` - JSX literals
    - `#queue` - Queue data structures
    - `#date` - Date literals
  - Changed `:auto-resolve` to use `name` function for better compatibility

- **scripts/test-parse-all.bb** - Testing utility for delimiter detection
  - Recursively finds and parses all Clojure files in a directory
  - Reports unknown tags with suggestions for adding readers
  - Helps validate edamame configuration across real codebases
  - Stops on first error with detailed reporting

- **Dynamic var for error handling** - `*signal-on-bad-parse*` (defaults to `true`)
  - Triggers parinfer on unknown tag errors as a safety net
  - Allows users to opt out via binding if needed
  - More defensive approach: better to attempt repair than skip

- **Expanded test coverage**
  - 30 tests (up from 27) with 165 assertions (up from 129)
  - New test suites for ClojureScript features:
    - `clojurescript-tagged-literals-test` - All supported tagged literals
    - `clojurescript-features-test` - Namespaced keywords and `::keys` destructuring
    - `mixed-clj-cljs-features-test` - Cross-platform code with reader conditionals
  - Tests validate both delimiter detection and proper parsing

### Changed
- Updated `bb.edn` to use cognitect test-runner instead of manual test loading
  - Cleaner test execution
  - Better output formatting
  - Standard Clojure tooling approach

### Removed
- **Legacy standalone .bb scripts** - Removed `clj-paren-repair-hook.bb` and `clojure-nrepl-eval.bb`
  - Now use `bb -m clojure-mcp-light.hook` and `bb -m clojure-mcp-light.nrepl-eval` instead
  - bbin installation uses namespace entrypoints from `bb.edn`
  - Eliminates 597 lines of duplicate code
  - Simpler maintenance with single source of truth

[0.0.2-alpha]: https://github.com/bhauman/clojure-mcp-light/releases/tag/v0.0.2-alpha

## [0.0.1-alpha] - 2025-11-08

### Added
- **clj-paren-repair-claude-hook** - Claude Code hook for automatic Clojure delimiter fixing
  - Detects delimiter errors using edamame parser
  - Auto-fixes with parinfer-rust
  - PreToolUse hooks for Write/Edit/Bash operations
  - PostToolUse hooks for Edit operations with backup/restore
  - Cross-platform backup path handling
  - Session-specific backup isolation

- **clj-nrepl-eval** - nREPL evaluation tool
  - Direct bencode protocol implementation for nREPL communication
  - Automatic delimiter repair before evaluation
  - Timeout and interrupt handling for long-running evaluations
  - Persistent session support with Claude Code session-id based tmp-file with `./.nrepl-session` file as fallback
  - `--reset-session` flag for session management
  - Port detection: CLI flag > NREPL_PORT env > .nrepl-port file
  - Formatted output with dividers

- **Slash commands** for Claude Code
  - `/start-nrepl` - Start nREPL server in background
  - `/clojure-eval` - Information about REPL evaluation

- **Installation support**
  - bbin package manager integration
  - Proper namespace structure (clojure-mcp-light.*)

- **Documentation**
  - Comprehensive README.md
  - CLAUDE.md project documentation for Claude Code
  - Example settings and configuration files
  - EPL-2.0 license

### Changed
- Logging disabled by default for hook operations
- Error handling: applies parinfer on all errors for maximum robustness

[0.0.1-alpha]: https://github.com/bhauman/clojure-mcp-light/releases/tag/v0.0.1-alpha
