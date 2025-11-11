---
name: clojure-eval
description: Evaluate Clojure code via nREPL using clj-nrepl-eval. Use this when you need to test code, check if edited files compile, verify function behavior, or interact with a running REPL session.
---

# Clojure REPL Evaluation

## When to Use This Skill

Use this skill when you need to:
- **Verify that edited Clojure files compile and load correctly**
- Test function behavior interactively
- Check the current state of the REPL
- Debug code by evaluating expressions
- Require or load namespaces for testing
- Validate that code changes work before committing

## How It Works

The `clj-nrepl-eval` command evaluates Clojure code against an nREPL server. **Session state persists between evaluations**, so you can require a namespace in one evaluation and use it in subsequent calls. Each host:port combination maintains its own session file.

## Instructions

### 0. Ask the user about the nREPL server

Ask the user what nREPL port to connect to or if they would like you to
start a nREPL server.

### 1. Evaluate Clojure Code

> Evaluation automatically connects to the given port

Use the `-p` flag to specify the port and pass your Clojure code:

**Recommended: Use heredoc by default (provides cleaner, more readable output):**
```bash
clj-nrepl-eval -p 7888 <<'EOF'
(+ 1 2 3)
EOF
```

**For multiline code:**
```bash
clj-nrepl-eval -p 7888 <<'EOF'
(def x 10)
(+ x 20)
EOF
```

**Alternative: As a command-line argument:**
```bash
clj-nrepl-eval --port 7888 "(+ 1 2 3)"
```

**Alternative: Via stdin pipe:**
```bash
echo "(+ 1 2 3)" | clj-nrepl-eval -p 7888
```

### 2. Display nREPL Sessions

You can check if you already have an active session on a port:

```bash
clj-nrepl-eval --connected-ports
```

Connections only appear in this list after you've evaluated code on
that port at least once and **connected to the port**

**Important:** This only shows connections you have made, not all nREPL
servers running on the system.

### 3. Common Patterns

**Require a namespace (always use :reload to pick up changes):**
```bash
clj-nrepl-eval -p 7888 <<'EOF'
(require '[my.namespace :as ns] :reload)
EOF
```

**Test a function after requiring:**
```bash
clj-nrepl-eval -p 7888 <<'EOF'
(ns/my-function arg1 arg2)
EOF
```

**Check if a file compiles:**
```bash
clj-nrepl-eval -p 7888 <<'EOF'
(require 'my.namespace :reload)
EOF
```

**Multiple expressions:**
```bash
clj-nrepl-eval -p 7888 <<'EOF'
(def x 10)
(* x 2)
(+ x 5)
EOF
```

**With custom timeout (in milliseconds):**
```bash
clj-nrepl-eval -p 7888 --timeout 5000 <<'EOF'
(long-running-fn)
EOF
```

**Reset the session (clears all state):**
```bash
clj-nrepl-eval -p 7888 --reset-session
clj-nrepl-eval -p 7888 --reset-session <<'EOF'
(def x 1)
EOF
```

## Available Options

- `-p, --port PORT` - nREPL port (required)
- `-H, --host HOST` - nREPL host (default: 127.0.0.1)
- `-t, --timeout MILLISECONDS` - Timeout (default: 120000 = 2 minutes)
- `-r, --reset-session` - Reset the persistent nREPL session
- `-c, --connected-ports` - List all active nREPL connections
- `-h, --help` - Show help message

## Important Notes

- **Prefer heredoc format:** Use heredoc (`<<'EOF' ... EOF`) by default for cleaner, more readable output - even for single-line expressions
- **Sessions persist:** State (vars, namespaces, loaded libraries) persists across invocations until the nREPL server restarts or `--reset-session` is used
- **Automatic delimiter repair:** The tool automatically repairs missing or mismatched parentheses
- **Always use :reload:** When requiring namespaces, use `:reload` to pick up recent changes
- **Default timeout:** 2 minutes (120000ms) - increase for long-running operations
- **Input precedence:** Command-line arguments take precedence over stdin

## Typical Workflow

1. Discover connections: `clj-nrepl-eval --connected-ports`
2. Require namespace:
   ```bash
   clj-nrepl-eval -p 7888 <<'EOF'
   (require '[my.ns :as ns] :reload)
   EOF
   ```
3. Test function:
   ```bash
   clj-nrepl-eval -p 7888 <<'EOF'
   (ns/my-fn ...)
   EOF
   ```
4. Iterate: Make changes, re-require with `:reload`, test again
