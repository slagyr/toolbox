# Toolbox

Portable, versioned component management for AI coding agents.

**The problem:** Agent components — skills, commands, and other reusable instructions — live at machine-specific paths (`~/.config/opencode/skills/`, `~/.claude/skills/`, `~/.agents/skills/`). When agents run on different machines, CI environments, or cloud workers, those paths don't exist. Your carefully crafted instructions break.

**The solution:** Declare components by URL in your project's boot file (`AGENTS.md`, `CLAUDE.md`, or whatever your agent reads on startup). Toolbox teaches agents how to fetch, cache, and update them. Works everywhere, no installation required.

## Quick Start

Add a `## Toolbox` section to your project's boot file (`AGENTS.md`, `CLAUDE.md`, etc.):

```markdown
## Toolbox

This project uses [toolbox](https://raw.githubusercontent.com/slagyr/toolbox/main/SKILL.md)
to manage agent components. If `.toolbox/` doesn't exist, fetch the toolbox
SKILL.md from the URL above and follow its instructions. Once bootstrapped,
load skills from `.toolbox/skills/{name}/SKILL.md` when their descriptions
match the task at hand. Commands are available at `.toolbox/commands/{name}.md`.

### Skills

- [tdd](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/tdd/SKILL.md)
- [braids](https://raw.githubusercontent.com/slagyr/braids/main/braids/SKILL.md)

### Commands

- [test](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/test.md)
- [deploy](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/deploy.md)
```

Add `.toolbox/` to your `.gitignore`. Done.

When an agent reads your boot file, it fetches the toolbox skill, learns how to resolve component URLs, and caches everything locally in `.toolbox/`.

## How It Works

1. Agent reads the boot file, finds the `## Toolbox` section
2. On first run, fetches each component into `.toolbox/`
3. Creates `.toolbox/toolbox.json` manifest with content hashes for change detection
4. On subsequent runs, uses the cached copies instantly
5. When you ask "check for updates", it compares remote content against stored hashes and reports what changed

Components are never auto-updated. You decide when to pull new versions.

## Component Types

### Skills

Skills are instruction sets with a `SKILL.md` entry point and optional reference files. Toolbox discovers references automatically by parsing relative markdown links in `SKILL.md`.

```
my-skill/
  SKILL.md
  references/
    details.md
    examples.md
```

`SKILL.md` has YAML frontmatter and markdown content:

```markdown
---
name: my-skill
description: One-line description of what this skill does and when to use it.
---

# My Skill

Instructions for the agent...

See: [references/details.md](references/details.md)
```

### Commands

Commands are single markdown files — no frontmatter, no references, no directory structure. A command named `test` is just `test.md`.

## Component Sources

Components can live anywhere accessible by URL.

### Public repos

```markdown
- [tdd](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/tdd/SKILL.md)
- [test](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/test.md)
```

### Local filesystem

For components under active development or private use:

```markdown
- [my-skill](file:///Users/me/Projects/my-skill/SKILL.md)
- [my-cmd](file:///Users/me/Projects/my-commands/my-cmd.md)
```

`file://` URLs aren't portable across machines — switch to `https://` when you publish.

### Pinned versions

Use a commit SHA instead of a branch name to lock a component to a specific version:

```markdown
- [tdd](https://raw.githubusercontent.com/slagyr/agent-lib/a1b2c3d/skills/tdd/SKILL.md)
```

## Update Detection

Toolbox stores a SHA-256 hash covering all of a component's files at fetch time. When you ask to check for updates, it re-fetches and compares hashes — only reporting components whose content has actually changed. No polling, no timers, no wasted fetches.
