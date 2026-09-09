# zrepl — Experiment Plan

> Project renamed from `nrepl-lsp` → `zrepl`. Lib coord:
> `io.github.just-sultanov/zrepl`. All command names use the `zrepl/` prefix.
>
> Note: S0.2 was revised in `docs/ai/features/s02.md` (own JSON-RPC over stdio instead of
> lsp4clj; malli + jsonista + tools.logging/logback).

## Goal

A Clojure LSP server, `zrepl`, that turns Zed into an interactive-repl
editor: keybindings like `, e f` evaluate the form under the cursor via a
REPL connection (MVP: nREPL) and render the result as an inlay hint
(`=> 4`) directly under the form. The same commands serve LLM agents (JSON
via `workspace/executeCommand` and a CLI).

**Spike success criterion (decision gate)**: cursor inside `(+ 2 2)` in a
Clojure buffer → press `, e f` → `=> 4` appears under the form within ~1s,
zero manual argument typing, server runs alongside clojure-lsp. If the spike
fails on Zed fundamentals, we stop and re-discuss.

## Channel model

- _Input (client → server)_: `workspace/executeCommand` is the only LSP way
  to tell the server to do something. Zed's keymap **cannot** invoke an LSP
  command directly with computed args, so the keybinding path is:
  `, e f` → `task::Spawn` → `zrepl` CLI → localhost IPC → same handler as
  executeCommand. Code actions and the command picker feed the same handler
  via real `executeCommand`.
- _Output (server → client)_: the command response JSON is only logged by
  Zed, so for humans results are rendered server→client: inlay hints
  (`=> 4` under the form), diagnostics (exceptions on the form's range),
  `window/showDocument` (long-output fallback, real files only).

## Command set (transport-agnostic)

Operations are named independently of the REPL backend; only `connect`
knows about transports. Adding a future backend (prepl, socket-repl) must
not change any existing command name or keybinding.

| Command           | nREPL op    | Notes                                                                                                                |
| ----------------- | ----------- | -------------------------------------------------------------------------------------------------------------------- |
| `zrepl/eval`      | `eval`      | enclosing top-level form at `[uri line character]` (0-based, clojure-lsp convention); selection → eval selected text |
| `zrepl/eval-code` | `eval`      | code string argument (picker / agents; no cursor needed)                                                             |
| `zrepl/load-file` | `load-file` | whole buffer/file                                                                                                    |
| `zrepl/interrupt` | `interrupt` | interrupt a pending eval                                                                                             |
| `zrepl/connect`   | —           | MVP: no args → connect via `.nrepl-port`; later optional args `["nrepl" host:port]` / `["prepl" ...]`                |
| `zrepl/stop`      | —           | close connection (does not kill the REPL server in MVP)                                                              |

JSON result of each command is machine-readable (`LSPAny`) and includes the
full printed value — the LLM path. `zrepl/` prefix avoids collisions with
clojure-lsp's bare command names (`clean-ns`, …).

## Future: multiple REPL transports (naming contract, do not break)

- Commands (above) are fixed forever; a backend is selected **only** at
  `zrepl/connect`.
- Internally: a `ReplClient` interface in `state.clj`
  (`eval`, `interrupt`, `load-file`, `describe`); MVP implementation
  `nrepl_client.clj` (nrepl.core). Future: `prepl_client.clj`,
  `socket_repl_client.clj` — each a new implementation + a branch in
  `connect`; nothing else changes.
- State shape: `{:transport :nrepl :conn ...}`.
- CLI mirrors commands: `zrepl eval --code ...`, `zrepl connect nrepl` —
  agents never need to know the backend.
- MVP uses `.nrepl-port` only; designing transport selection is deferred.

## Verified facts (from repos/zed @ 52b2927a1b, repos/nrepl, repos/clojure-lsp)

Zed:

- `executeCommandProvider` commands appear in the LSP command picker
  (`lsp_command_selector::Toggle`); arguments: JSON array / multiple JSON
  values / plain text → single string (`lsp_command_selector.rs:175`).
- Zed executes `CodeAction.command` via executeCommand after checking it is
  listed in `execute_command_provider` (`crates/project/src/lsp_store.rs:2365`);
  same for code lens commands (`lsp_store.rs:3505`).
- Code action requests carry the **current selection** range
  (`refresh_code_actions_for_selection`, `editor/src/code_actions.rs:379`):
  empty selection = cursor point → "Eval top-level form"; non-empty →
  "Eval selection".
- `workspace/inlayHint/refresh` (server→client) is fully wired:
  `lsp_store.rs:1120` → event `RefreshInlayHints` (`lsp_store.rs:4630`) →
  `editor.rs:2092` → `inlays/inlay_hints.rs:343`; reason `RefreshRequested`
  sets `ignore_previous_fetches = true` and re-requests only visible ranges
  (`inlay_hints.rs:376-382`). Debounced (`append_debounce` /
  `invalidate_debounce`).
- `window/showDocument`: real file:// URIs only, with `selection`,
  `takeFocus`, `external` (`editor/src/items.rs:2515`).
- Task system: `task::Spawn` is keymap-bindable with args
  (`default-macos.json:785`); task variables include `$ZED_FILE`,
  `$ZED_RELATIVE_FILE`, `$ZED_ROW`, `$ZED_SELECTED_TEXT` (used in
  `terminal_panel.rs:1955`, `task_template.rs`).
- Keymap supports multi-key sequences (`"space e"` in docs; engine:
  `possible_next_bindings_for_input`, `gpui/src/keymap.rs:254`) and
  per-file-type context: editor key context sets `extension`
  (`editor.rs:2832`), docs example `Editor mode=full extension=md`.
  Context view: `dev::OpenKeyContextView`.
- `workspace::SendKeystrokes` exists for macro-style fallback
  (`workspace.rs:604`).
- Multiple language servers per language are supported (zrepl runs
  alongside clojure-lsp; commands from all servers merge in the picker).
- Server binary path is user-configurable: `lsp.<name>.binary.path`.

nREPL:

- Official client: `(nrepl/connect)` → `(nrepl/client conn timeout)` →
  `(nrepl/message {:op "eval" :code ...})`; ops `eval`, `load-file`,
  `clone`, `close`, `interrupt`, `describe`. Plain Java (bencode) —
  GraalVM-safe. Servers advertise port in `.nrepl-port`.

Clojure/GraalVM precedent:

- clojure-lsp = Clojure + lsp4clj + GraalVM native-image
  (`cli/build.clj`: `--no-fallback`,
  `--features=clj_easy.graal_build_time.InitClojureClasses`, reflect/proxy
  configs, `-J-Xmx8g`).
- Local toolchain: clojure CLI, babashka, clj-kondo via mise; Java 25
  (Temurin); GraalVM via `brew install graalvm-jdk`.

## Architecture

```
Zed ──(stdio LSP)── zrepl ──(bencode TCP, nrepl.core)── nREPL server
 │                                                      (project)
 ├── picker / code actions ──executeCommand──┐
 ├── tasks+keymap ──shell── zrepl CLI ──HTTP localhost──┘ (same handler)
 └── inlayHints ← refresh, publishDiagnostics, showDocument ← results
```

zrepl owns: nREPL connection (MVP: connect-only via `.nrepl-port`), session
state, eval results atom per `[uri range]`, output ring buffer files, a
small localhost IPC port (`.zrepl/port`) shared by CLI calls.

## Project layout (actual scaffold)

Bootstrap already exists (see Progress log); conventions live in
`agents.md` — follow it.

```
zrepl/
  docs/ai/plan.md
  docs/ai/features/s02.md
  agents.md             # toolchain + conventions (source of truth)
  deps.edn              # aliases: :develop :test :build :nop :outdated
  mise.toml             # tasks → ./bin/*
  bin/                  # clean lint test build install publish repl deps
  build.clj             # tools.build; lib io.github.just-sultanov/zrepl
  tests.edn             # Kaocha; ^:unit focus-meta
  src/main/clojure/zrepl/    # server source (main.clj server.clj handlers.clj
                             #  commands.clj nrepl_client.clj forms.clj
                             #  output.clj ipc.clj state.clj)
  src/test/clojure/     # Kaocha tests
  src/develop/clojure/  # dev-only (nREPL, logging)
  zed-extension/        # NOT CREATED YET: dev extension (Rust/WASM)
  zed/                  # NOT CREATED YET: keymap.json, tasks.json, settings.json snippets
  repos/                # vendored refs (zed, nrepl, clojure-lsp) — not part of the lib
```

## Spike-0 — prove the exact UX end-to-end (throwaway-quality code OK)

- [x] **S0.1 Bootstrap** — DONE by hand on 2026-09-09: git repo, deps.edn
      (aliases develop/test/build/nop/outdated), mise.toml + bin/ tasks,
      build.clj (`io.github.just-sultanov/zrepl`), tests.edn (Kaocha),
      `zrepl.core` + dummy `^:unit` test, agents.md, readme.md. Do NOT
      redo. S0.2 was re-scoped on 2026-09-10: no lsp4clj — own JSON-RPC
      over stdio, malli + jsonista, tools.logging + logback (stderr);
      details in `docs/ai/features/s02.md`.
- [ ] **S0.2 LSP skeleton**: own JSON-RPC over stdio (revised 2026-09-10,
      see `docs/ai/features/s02.md`); `initialize` (capabilities:
      `executeCommandProvider: ["zrepl/eval"]`, `inlayHintProvider`),
      `shutdown`/`exit`; verify with a scripted JSON-RPC stdio client.
      Server namespaces under `src/main/clojure/zrepl/`.
- [ ] **S0.3 Zed registration**: minimal dev extension
      (`zed-extension/`, ~30 lines Rust) registering `zrepl` for Clojure;
      install as dev extension; binary path via `settings.json`; verify
      server starts alongside clojure-lsp.
- [ ] **S0.4 Buffer tracking**: `didOpen`/`didChange` keep buffer text in
      an atom; all other notifications no-op.
- [ ] **S0.5 nREPL eval**: read `.nrepl-port`, connect, clone one session,
      eval code from command args (plain string first), reduce responses to
      `{:value :out :err :status}`; test by invoking `zrepl/eval` from the
      picker with a code string.
- [ ] **S0.6 Inlay hints**: handler returns hints from the results atom
      (position: end of evaluated form); after each eval send
      `workspace/inlayHint/refresh`; verify `=> <value>` appears without
      touching the keyboard.
- [ ] **S0.7 IPC + CLI**: server listens on localhost HTTP
      (`POST /eval {code}`), writes `.zrepl/port`; CLI subcommand
      `zrepl eval --code "..."` posts there; verify curl triggers a hint
      refresh in Zed.
- [ ] **S0.8 Keybindings**: `zed/tasks.json` (`zrepl: eval` task using
      `$ZED_FILE`/`$ZED_ROW`/`$ZED_COLUMN`, args-array form to dodge shell
      quoting of `$ZED_SELECTED_TEXT`); user keymap:
      `json
  { "context": "Editor && extension == clj",
    "bindings": {
      ", e f": ["task::Spawn", { "task_name": "zrepl: eval" }]
    } }
  `
      **Also test**: typing `,` inside a Clojure buffer in insert mode —
      if the pending sequence delays comma insertion, fall back to
      `vim_mode == normal && extension == clj` context (vim users) or a
      `cmd-k`-style chord; document the choice.
- [ ] **S0.9 Gate check**: press `, e f` on `(+ 2 2)` → `=> 4` under the
      form ≤ ~1s. Record what broke (if anything) in Progress log; decide
      continue/stop.

## Milestones (after the gate)

### M1 — real implementation of the spike

- [ ] **M1.1 Form extraction**: rewrite-clj; enclosing top-level form at
      `[line, character]` + its range; selection → evaluated text.
- [ ] **M1.2 Commands**: `zrepl/eval [uri line character]`,
      `zrepl/eval-code [code]`; `codeActionProvider` with static actions
      "Eval top-level form" / "Eval selection" carrying ready-made
      `command` + arguments.
- [ ] **M1.3 Connection lifecycle**: connect on initialize / lazy reconnect;
      `window/showMessage` on missing `.nrepl-port` / dead server;
      `zrepl/connect`, `zrepl/stop` commands.
- [ ] **M1.4 Diagnostics**: exceptions → `publishDiagnostics` on the form's
      range (message + top stack frames); cleared on next eval.
- [ ] **M1.5 Tests**: unit (form extraction, response reduction); stdio
      JSON-RPC integration test; fake nREPL via `nrepl.server/start-server`
      in-process.
- [ ] **M1.6 Ship config snippets**: `zed/` dir in repo with keymap,
      tasks.json, settings.json examples; README quickstart.

### M2 — session completeness

- [ ] **M2.1** `zrepl/load-file`, `zrepl/interrupt`.
- [ ] **M2.2** Output ring buffer (`.zrepl/output/`) + `showDocument`
      fallback for long stdout/values (last-result file command).
- [ ] **M2.3** Stale-result invalidation on buffer edits (range tracking).
- [ ] **M2.4** Streaming `:out`/`:err` handling; truncation policy for hints
      (first line, ~120 chars).

### M3 — native binary

- [ ] **M3.1** AOT jar via `tools.build` (modeled on
      `repos/clojure-lsp/cli/build.clj`).
- [ ] **M3.2** GraalVM native-image: `graal-build-time`, reflect/proxy
      configs, `--no-fallback`, macOS arm64 first.
- [ ] **M3.3** Full-flow smoke test on the native binary (Zed → eval →
      hint), startup time measurement.

### M4 — agent ergonomics

- [ ] **M4.1** README: setup, keybindings, command reference written for
      LLM consumption (argument conventions, examples).
- [ ] **M4.2** CLI verbs: `load-file`, `interrupt`, `output`.
- [ ] **M4.3** Optional: publish the Zed extension; consider upstreaming a
      generic "execute code action by name / run LSP command with cursor
      args" action to Zed.

## Risks / open questions

- **Comma prefix vs typing** (S0.8): pending multi-key sequence may delay
  `,` insertion in insert mode; mitigations: vim-mode context or `cmd-k`
  chord. Decided by experiment, documented in S0.8.
- **Hint debounce**: results render after Zed's append/invalidate debounce
  (hundreds of ms) — acceptable; noted so the 1s gate is fair.
- **Two servers on one language**: verify clojure-lsp diagnostics/hints and
  ours coexist (S0.3/S0.6); `zrepl/` namespacing avoids picker collisions.
- **Task variable quoting**: `$ZED_SELECTED_TEXT` containing quotes/parens
  must not break the shell — use tasks `args` array; verify in S0.8.
- **GraalVM**: reflection configs iterate like clojure-lsp's did; ~8 GB
  build heap.
- **Transport growth** (prepl/socket-repl): commands and keybindings stay
  fixed; only `connect` and a new `ReplClient` implementation are added
  (see "Future: multiple REPL transports").

## Progress log / session state

- 2026-09-09 (session 1): plan created; spike-first restructure after
  verifying the inlay-refresh chain, task variables, and keymap sequences
  in Zed source. Decisions: spike-first gate S0.9; channel model (one
  input `executeCommand`, outputs = inlay hints / diagnostics /
  showDocument-fallback); transport-agnostic `zrepl/*` commands (option B)
  with MVP = `.nrepl-port` connect-only; project renamed `nrepl-lsp` →
  `zrepl`.
- 2026-09-09 (bootstrap): S0.1 done by hand — git repo, deps.edn, mise +
  bin/, build.clj, tests.edn, `zrepl.*` namespaces, agents.md. Uncommitted
  at session end.
- Next session: start at **S0.2** (LSP skeleton — own JSON-RPC, see
  `docs/ai/features/s02.md`). Working directory will be renamed to `…/zrepl`.
