---
name: maintain-docs
description: Update Natune's maintained Markdown after behavior, build, workflow or release changes. Use when documentation is stale or a code change affects agent instructions.
license: GPL-3.0-only
---

# Maintain project documentation

1. Read `AGENTS.md` and the relevant subsystem document. Invariants belong in
   `AGENTS.md`; `CLAUDE.md` only points to it.
2. Put product knowledge in `docs/claude/`, development and runbook details in
   `docs/engineering/`, and reusable workflows beside this skill.
3. Check the actual code and configuration before you change a command or a feature
   claim. An old green run does not validate a new change.
4. Update only the documents the change affects. Describe current state; do not add
   session logs, dated progress notes, duplicate plans or speculative claims. Delete a
   document that no longer describes anything current, after fixing links to it.
5. Keep copyright, licence and provenance notes. Distinguish implemented, compiled,
   tested and device-verified behavior.
6. Run `python3 scripts/check_repository.py --worktree` to validate links, skill
   frontmatter and documented CI commands. Fix failures; do not disable the checks.

Finish with the changed paths, the checks you ran and any remaining gaps.
