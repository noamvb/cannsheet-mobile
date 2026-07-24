# Architectural decision log

This file records durable decisions that future work must understand. Add an ADR
only when a meaningful decision has been made; do not reconstruct unsupported
historical rationale.

## ADR-001: Keep cross-agent context in the Git repository

- Status: Accepted
- Date: 2026-07-23
- Context: Coding agents, accounts, and sessions cannot be assumed to share
  conversations, task history, memory, or hidden context. Project work needs a
  durable, reviewable handoff mechanism that travels with the source.
- Decision: The Git repository is the canonical source of shared context between
  coding agents. Required operational guidance belongs in `AGENTS.md`; current
  implementation state belongs in `docs/PROJECT_STATE.md`; durable technical
  decisions belong in `docs/DECISIONS.md`; and the latest cross-agent transfer
  state belongs in `docs/HANDOFF.md`. Vendor adapters such as `GEMINI.md` import
  or point to these canonical files without duplicating them. Important
  discoveries made during an agent session must be written back into the
  appropriate repository document rather than left only in a conversation.
- Rationale: Repository content is versioned, reviewable, available to every
  account with the checkout, and can be checked against code and configuration.
- Consequences: Agents must read the shared-context files before substantial
  work, keep them concise and evidence-based, and update them when their subject
  changes. `docs/HANDOFF.md` is replaceable latest state; Git history preserves
  earlier handoffs.
- Related files: `AGENTS.md`, `docs/PROJECT_STATE.md`,
  `docs/ARCHITECTURE.md`, `docs/HANDOFF.md`, `GEMINI.md`,
  `.agents/skills/project-handoff/SKILL.md`
