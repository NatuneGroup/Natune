# CLAUDE.md

Read [AGENTS.md](AGENTS.md). It is the single source for project layout, invariants,
build commands and working rules; nothing here overrides it.

Claude Code specifics:

- Shared permissions are in `.claude/settings.json`. Put personal overrides in the
  ignored `.claude/settings.local.json`.
- `.claude/skills` links to [.factory/skills/](.factory/skills/); edit skills there and
  do not create copies.
- Product notes for longer tasks are in [docs/claude/](docs/claude/):
  [ARCHITECTURE](docs/claude/ARCHITECTURE.md), [SETTINGS](docs/claude/SETTINGS.md) and
  [RELEASES](docs/claude/RELEASES.md).
