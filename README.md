# Toolbox

Portable, versioned skill management for AI coding agents.

**The problem:** Agent skills live at machine-specific paths (`~/.config/opencode/skills/`, `~/.claude/skills/`, `~/.agents/skills/`). When agents run on different machines, CI environments, or cloud workers, those paths don't exist. Your carefully crafted instructions break.

**The solution:** Declare skills by URL in your project's boot file (`AGENTS.md`, `CLAUDE.md`, or whatever your agent reads on startup). Toolbox teaches agents how to fetch, cache, and update them. Works everywhere, no installation required.

## Quick Start

Add a `## Skills` section to your project's boot file (`AGENTS.md`, `CLAUDE.md`, etc.):

```markdown
## Skills

This project uses [toolbox](https://raw.githubusercontent.com/slagyr/toolbox/main/SKILL.md)
to manage skills. If `.skills/` doesn't exist, fetch the toolbox SKILL.md
from the URL above and follow its instructions. Once bootstrapped, load
skills from `.skills/{name}/SKILL.md` when their descriptions match the
task at hand.

- [tdd](https://raw.githubusercontent.com/slagyr/agent-skills/main/tdd/SKILL.md)
- [braids](https://raw.githubusercontent.com/slagyr/braids/main/braids/SKILL.md)
```

Add `.skills/` to your `.gitignore`. Done.

When an agent reads your boot file, it fetches the toolbox skill, learns how to resolve skill URLs, and caches everything locally in `.skills/`.

## How It Works

1. Agent reads the boot file, finds the `## Skills` section
2. On first run, fetches each skill's `SKILL.md` (and any linked references) into `.skills/`
3. Creates `.skills/toolbox.json` manifest with content hashes for change detection
4. On subsequent runs, uses the cached copies instantly
5. When you ask "check for skill updates", it compares remote content against stored hashes and reports what changed

Skills are never auto-updated. You decide when to pull new versions.

## Skill Sources

Skills can live anywhere accessible by URL.

### Public repos

```markdown
- [tdd](https://raw.githubusercontent.com/slagyr/agent-skills/main/tdd/SKILL.md)
- [braids](https://raw.githubusercontent.com/slagyr/braids/main/braids/SKILL.md)
```

### Local filesystem

For skills under active development or private skills:

```markdown
- [my-skill](file:///Users/me/Projects/my-skill/SKILL.md)
```

`file://` URLs aren't portable across machines — switch to `https://` when you publish.

### Pinned versions

Use a commit SHA instead of a branch name to lock a skill to a specific version:

```markdown
- [tdd](https://raw.githubusercontent.com/slagyr/agent-skills/a1b2c3d/tdd/SKILL.md)
```

## Writing Skills

A skill is a directory with a `SKILL.md` entry point and optional reference files:

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

Toolbox discovers reference files automatically by parsing relative markdown links in `SKILL.md`. No manifest needed from skill authors.

## Update Detection

Toolbox stores a SHA-256 hash of each skill's `SKILL.md` at fetch time. When you ask to check for updates, it re-fetches and compares hashes — only reporting skills whose content has actually changed. No polling, no timers, no wasted fetches.
