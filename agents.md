# AGENTS.md — zrepl

## Toolchain

- **deps.edn** — no Leiningen, no `project.clj`. All tooling via `clojure` CLI + `mise`.
- **mise** — version manager and task runner. Install tools with `mise install`.
- Managed tools: `clojure`, `clj-kondo`, `clojure-lsp`, `cljfmt`, `rust`
  (1.97.1 + wasm32-wasip2 — pin must match `repos/zed/rust-toolchain.toml`).

## Source Layout

```
src/main/clojure    — project source
src/test/clojure    — tests
src/develop/clojure — development only code
```

Not the default `src/` convention — always use the paths above.

## Commands

All via `mise run <task>`:

| Task                    | What it does                                     |
| ----------------------- | ------------------------------------------------ |
| `mise run clean`        | Remove `target/`                                 |
| `mise run lint`         | `clojure-lsp diagnostics` + `clojure-lsp format` |
| `mise run test`         | Run Kaocha test runner                           |
| `mise run build`        | Clean + build JAR (`target/*.jar`)               |
| `mise run install`      | Build + install to local Maven repo              |
| `mise run publish`      | Build + deploy to Clojars                        |
| `mise run repl`         | Start nREPL with Cider middleware                |
| `mise run deps:check`   | Check outdated deps (antq)                       |
| `mise run deps:upgrade` | Upgrade outdated deps                            |
| `mise run zed:build`    | Build Zed from `repos/zed` (release, one-off)    |
| `mise run zed:ext:check`| Type-check `zed-extension/` for wasm32-wasip2    |

## CI Order

CI runs sequentially: **lint → test → build**. Test depends on lint passing. Build depends on tests passing.

## Testing

- Test runner: **Kaocha** (config in `tests.edn`)
- Tests are tagged `^:unit` — Kaocha filters on `:focus-meta [:unit]`
- Run subset: `mise run test --focus my-test-name`
- Coverage output: `target/coverage/` (HTML + Codecov JSON)
- JUnit XML: `target/coverage/junit.xml`

## Build

- `build.clj` — tools.build script, invoked via `-T:nop:build`
- Project coord: `io.github.just-sultanov/zrepl`
- Version derived from git commit count: `0.1.{patch}`
- `:nop` alias adds `slf4j-nop` to suppress logging during build commands
- Publish requires `CLOJARS_USERNAME` / `CLOJARS_PASSWORD` env vars
