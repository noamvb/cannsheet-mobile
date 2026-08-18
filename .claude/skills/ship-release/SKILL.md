---
name: ship-release
description: Take a change from an empty working tree to a signed APK the phone owner can install through Obtainium — branch, validate, pull request, prove the exact main SHA is green, bump the version, tag, publish, and verify. Use when the task is to implement a change and release it, not for an ordinary pull request.
---

# Ship a release

**Open `.agents/skills/ship-release/SKILL.md` and read it in full before taking
any action.** That file is the skill. This file is a short vendor adapter that
exists only so `/ship-release` resolves in Claude Code, and it is deliberately
not a summary you can work from — the toolchain exports, the validation gate,
the tag requirements, the publication checks, and the phone hand-off appear
only in the canonical file.

Read `AGENTS.md` as well.

Two things to carry into that read, because they are what actually break
releases in this repository:

- Publication requires the **exact tagged commit SHA** to have a completed,
  successful **push-to-main** run with all six named jobs green. A green
  pull-request check never qualifies, because pull requests do not run the
  Emulator API 36 job that the publish workflow requires by name.
- Back-to-back merges cancel each other's main runs, and `cancelled` is not
  `success`. Land every commit first, confirm the final `main` SHA is green,
  then tag that SHA.

Publication is public and irreversible. Confirm with the repository owner
before pushing a tag unless they have already asked for this specific release.

If you have not yet opened `.agents/skills/ship-release/SKILL.md`, you are not
following this skill.
