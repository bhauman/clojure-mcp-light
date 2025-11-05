---
description: Start an nREPL server in the background
---

When the user invokes this command, start an nREPL server in the background by following these steps:

## Step 1: Check CLAUDE.md for REPL Instructions

First, check if a `CLAUDE.md` file exists in the project root:

1. If `CLAUDE.md` exists, read it and look for a section about starting the REPL or nREPL
2. Look for keywords like "REPL", "nREPL", "start", "run", or similar
3. If specific instructions are found, follow those instructions instead of the default steps below

## Step 2: Check Environment

First, verify that nREPL is configured in the project:

1. Check if `deps.edn` exists and contains an `:nrepl` alias
2. Check if `project.clj` exists (Leiningen project)

If neither file exists or nREPL is not configured:
- Inform the user that nREPL is not configured
- Ask if they want you to add the nREPL configuration to `deps.edn`

## Step 3: Check for Existing nREPL Server

Before starting a new server:

1. Check if `.nrepl-port` file exists
2. If it exists, try to connect to verify if server is still running
3. If a server is already running, inform the user and display the port
4. Ask if they want to stop the existing server and start a new one

## Step 4: Start nREPL Server

Start the nREPL server in the background WITHOUT specifying a port (let nREPL auto-assign an available port):

**For deps.edn projects:**
```bash
clojure -M:nrepl
```

**For Leiningen projects:**
```bash
lein repl :headless
```

Use the Bash tool with `run_in_background: true` to start the server.

## Step 5: Extract Port from Output

1. Wait 2-3 seconds for the server to start
2. Use the BashOutput tool to check the startup output
3. Parse the port number from output like: "nREPL server started on port 54321..."
4. Extract the numeric port value

## Step 6: Create .nrepl-port File

Write the extracted port number to `.nrepl-port` file using:
```bash
echo "PORT_NUMBER" > .nrepl-port
```

Replace PORT_NUMBER with the actual port extracted from the output.

## Step 7: Verify and Report

1. Confirm the `.nrepl-port` file was created successfully
2. Display to the user:
   - The port number the server is running on
   - The connection URL (e.g., `nrepl://localhost:PORT`)
   - The background process ID
   - Note that `clojure-nrepl-eval.bb` can now auto-detect the port

## Example Output to User

```
nREPL server started successfully!

Port: 54321
URL: nrepl://localhost:54321
Background process: a12345
Port file: .nrepl-port

You can now use ./clojure-nrepl-eval.bb to evaluate Clojure code.
```

## Error Handling

- If the server fails to start, display the error output
- If port cannot be parsed, show the raw output and ask user to check manually
- If `.nrepl-port` file cannot be created, inform the user
