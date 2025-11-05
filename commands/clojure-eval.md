---
description: Info on how to evaluate Clojure code via nREPL using clj-nrepl-eval
---

When you need to evaluate Clojure code you can use the
`clj-nrepl-eval` command (if installed via bbin) or the
`clojure-nrepl-eval.bb` script to evaluate code against an nREPL server.
This means the state of the REPL session will persist between
evaluations.

You can require or load a file in one evaluation of the command and
when you call the command again the namespace will still be available.

## Example uses

You can evaluate clojure code to check if a file you just edited still compiles and loads.

Whenever you require a namespace always use the `:reload` key.

## How to Use

The following evaluates Clojure code via an nREPL connection.

**With bbin installation:**
```bash
clj-nrepl-eval "<clojure-code>"
```

**Without bbin (manual installation):**
```bash
./clojure-nrepl-eval.bb "<clojure-code>"
```

## Options

- `-p, --port PORT` - Specify nREPL port (if not using .nrepl-port or NREPL_PORT env)
- `-H, --host HOST` - Specify nREPL host (default: 127.0.0.1)
- `-t, --timeout MILLISECONDS` - Set timeout in milliseconds (default: 120000)
- `-h, --help` - Show help message

## Examples

**Basic evaluation (auto-detects port):**
```bash
clj-nrepl-eval "(+ 1 2 3)"
```

**With specific port:**
```bash
clj-nrepl-eval --port 7888 "(println \"Hello\")"
```

**With timeout:**
```bash
clj-nrepl-eval --timeout 5000 "(Thread/sleep 10000)"
```

**Multiple expressions:**
```bash
clj-nrepl-eval "(def x 10) (* x 2) (+ x 5)"
```

## Features

- **Auto-detects port** from .nrepl-port file or NREPL_PORT environment variable
- **Automatic delimiter repair** - fixes missing/mismatched parens before evaluation
- **Timeout handling** - interrupts long-running evaluations
