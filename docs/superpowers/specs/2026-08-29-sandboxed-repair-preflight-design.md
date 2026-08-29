# Sandboxed Repair Preflight Design

## Goal

Prevent a candidate repair from modifying the real project until it has passed the incident command in an isolated temporary copy of the trusted project.

## Scope

This change affects the Python repair path only. It does not add an Android pipeline UI, containers, virtual machines, or remote execution.

## Architecture

`PatchManager` will use a new `SandboxRunner` before it creates a live-file snapshot or writes to the actual repair target.

1. The relay resolves the repair target through the incident's trusted canonical project root, as it does for protocol v2 repairs.
2. `PatchManager` creates a temporary sandbox directory and copies the incident project into it. The copy excludes `.git`, Python caches, virtual environments, DevDeck snapshots, and backup files.
3. The candidate single-line replacement or unified diff is applied only to the sandboxed copy.
4. The original incident command is run with the sandbox as its working directory and a 15-second timeout.
5. A failed command, malformed candidate patch, or timeout discards the sandbox and rejects the repair. The real target remains byte-identical.
6. Only a successful sandbox result enters the existing live flow: hash check, snapshot, write, live command re-run, and rollback on failure.

Legacy repairs without a trusted project root retain their existing behavior. Protocol v2 repairs must use the sandbox preflight.

## Interfaces

`SandboxRunner` is responsible only for building, executing, and deleting the temporary project copy. It returns a small result object containing:

- whether the verification command passed;
- exit code when available;
- capped stdout and stderr diagnostics;
- a distinct timeout result.

`PatchManager` remains the public repair entry point. It will derive the patched candidate content before the live write, invoke the sandbox runner, and return a clear failure message when sandbox verification rejects it.

## Error Handling and Safety

- Sandbox cleanup occurs in `finally`, including after timeouts and exceptions.
- The sandbox path is temporary and never supplied by the client.
- Copy exclusions avoid recursive or heavyweight local artifacts.
- Verification output is capped before returning it to avoid oversized relay messages.
- The existing live snapshot rollback remains mandatory because sandbox and live environments can differ.

## Tests

- A failing sandbox command leaves the real file byte-identical and does not create a live snapshot.
- A successful sandbox command allows the existing live repair and verification path to run.
- A sandbox timeout rejects the repair without modifying the real file.
- Both single-line and unified-diff candidate repairs are validated in the sandbox.
- Existing protocol v2 trusted-root and hash-integrity regression tests remain green.

## Demo Story

DevDeck presents two independent repair gates: an isolated sandbox validates the proposed repair before the working copy is touched, then live verification confirms it in the real environment with instant rollback available.
