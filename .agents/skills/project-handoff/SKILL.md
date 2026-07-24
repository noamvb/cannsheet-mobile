---
name: project-handoff
description: Prepare and record a concise repository-based handoff before work moves to another coding agent, account, or session.
---

# Project handoff

Use this skill when work is about to move to another coding agent, account, or
session. Keep the handoff based on the repository and current command results,
not on assumed access to conversation history or account memory.

1. Read `AGENTS.md`, `docs/PROJECT_STATE.md`, `docs/DECISIONS.md`, and the
   current `docs/HANDOFF.md`.
2. Inspect `git status --short --branch` and the current branch.
3. Review the session's complete diff, including untracked files.
4. Run the relevant available validation without invoking destructive,
   deployment, publishing, migration, or external-service operations.
5. Record exact commands, outcomes, and any validation that was not performed.
6. Update `docs/PROJECT_STATE.md` if the verified implementation state changed.
7. Add an ADR to `docs/DECISIONS.md` only for a significant durable decision.
8. Replace `docs/HANDOFF.md` with a concise latest-state transfer containing
   completed work, current state, unfinished work, risks, assumptions, relevant
   files, and the recommended next action.
9. Check the final diff for secrets, credentials, personal machine paths,
   generated artifacts, and unrelated changes.
10. Summarize the unfinished work, risks, validation boundary, and recommended
    next step for the receiving account.

Do not claim that unexecuted validation passed. Do not append a session diary to
`docs/HANDOFF.md`; Git history preserves older versions.
