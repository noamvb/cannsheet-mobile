# Gemini CLI project adapter

The Git repository is the canonical source of shared project context. Gemini
must not assume access to Codex conversations, memories, task history, or hidden
account context.

@./AGENTS.md
@./docs/PROJECT_STATE.md
@./docs/HANDOFF.md

Follow every operational rule in `AGENTS.md`. Before substantial planning or
implementation, also read `docs/ARCHITECTURE.md` and the relevant entries in
`docs/DECISIONS.md`.

Write important discoveries, implementation-state changes, durable decisions,
and cross-agent handoffs back into the corresponding canonical repository
documents. Refresh `docs/HANDOFF.md` before transferring work.

Keep this file as a thin Gemini adapter. Do not duplicate detailed project
context here or add credentials, personal paths, account-specific settings,
model preferences, or unsupported Gemini configuration.
