---
name: ship-release
description: Take a change from an empty working tree to a signed APK the phone owner can install through Obtainium — branch, validate, pull request, prove the exact main SHA is green, bump the version, tag, publish, and verify. Use when the task is to implement a change and release it, not for an ordinary pull request.
---

# Ship a release

This is a vendor adapter. The canonical instructions live in the repository and
apply to every coding agent.

Read and follow `.agents/skills/ship-release/SKILL.md` in full before taking any
action, together with `AGENTS.md`.

Two points worth carrying into your first read, because they are what actually
break releases here:

- Publication requires the **exact tagged commit SHA** to have a completed,
  successful **push-to-main** run with all six named jobs green. A green
  pull-request check does not qualify — pull requests never run the Emulator
  API 36 job that the publish workflow requires by name.
- Back-to-back merges cancel each other's main runs, and `cancelled` is not
  `success`. Land every commit first, confirm the final `main` SHA is green,
  then tag that SHA.
