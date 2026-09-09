# zrepl

## Overview

`zrepl` is an experimental LSP server that brings REPL-driven development to
Zed. It bridges the editor and your Clojure project's nREPL server: evaluate
the form under the cursor with a single keybinding, see the result as an
inlay hint (`=> 4`) directly under the form, and get exceptions as
diagnostics. The same commands are available programmatically through
`workspace/executeCommand` and a small CLI, so LLM agents can drive the
REPL without knowing the nREPL wire protocol.

Status: experiment — design and milestones live in
`docs/ai/plan.md` (feature plans in `docs/ai/features/`).

## Installation

TBD

## Quick Start

TBD

## License

Copyright © 2026-present Ilshat Sultanov

Distributed under the Eclipse Public License version 1.0.
