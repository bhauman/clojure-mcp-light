# Tests

This directory contains tests for clojure-mcp-light.

## Running Tests

Run all tests:
```bash
bb test
```

## Test Structure

- `delimiter_repair_test.clj` - Tests for delimiter detection and repair functionality
- `claude_hook_test.clj` - Tests for Claude Code hook processing
- `codex_hook_test.clj` - Tests for Codex apply_patch hook processing
- `nrepl_eval_test.clj` - Tests for nREPL evaluation utilities

## Test Coverage

### delimiter-repair namespace
- ✅ Delimiter error detection
- ✅ Delimiter repair with parinfer
- ✅ Edge cases (empty strings, multiple forms)

### claude-hook namespace
- ✅ Clojure file detection
- ✅ Backup path generation
- ✅ Hook processing for Write operations
- ✅ Hook processing for Edit operations
- ✅ Auto-fixing delimiter errors

### codex-hook namespace
- ✅ apply_patch file path extraction
- ✅ PreToolUse backup creation
- ✅ PostToolUse delimiter repair

### nrepl-eval namespace
- ✅ Byte to string conversion
- ✅ Type coercion
- ✅ Port and host resolution
- ✅ Message parsing

## Adding New Tests

1. Create a new test file in `test/clojure_mcp_light/`
2. Add the namespace to the test task in `bb.edn`
3. Run `bb test` to verify

Test files should follow the naming convention `*_test.clj` and use `clojure.test`.
