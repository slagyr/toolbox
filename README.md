# Toolbox

Portable, versioned, project-local component management for AI coding agents.

## Usage

1. Tell your agent to use Toolbox: 
```txt
Please use [Toolbox](https://raw.githubusercontent.com/slagyr/toolbox/main/SKILL.md) to manage skills and components for this directory.
```

2. Tell your agent to add new tools (skills, commands, or rules)
```txt
Please add <link to SKILL.md> to my toolbox.
```

3. Get the latest version of tools and install them in the right place. 
```txt
Please update my toolbox.
```

## Introduction

**The problem:** Agent components — skills, commands, and other reusable instructions — often live at machine-specific paths (`~/.config/opencode/skills/`, `~/.claude/skills/`, `~/.agents/skills/`) or agent-specific project paths. If files are not in the exact place a given agent expects, discovery fails.

**The solution:** Declare components by URL in your project's boot file (`AGENTS.md`, `CLAUDE.md`, or whatever your agent reads on startup). Toolbox fetches and caches them in `.toolbox/` (source of truth), then projects them into agent-specific, project-local paths (for example `.claude/` and `.opencode/`) so discovery works. No global installation required.

**Light token footprint:** The boot file section adds ~300-400 tokens to your agent's context — comparable to a few lines of project instructions. The full toolbox spec is only loaded during first-run bootstrap or updates, never on every session.

## Quick Start

Add a `## Toolbox` section to your project's boot file (`AGENTS.md`, `CLAUDE.md`, etc.):

```markdown
## Toolbox

This project uses [toolbox](https://raw.githubusercontent.com/slagyr/toolbox/main/SKILL.md)
to manage agent components. If `.toolbox/` doesn't exist, fetch the toolbox
SKILL.md from the URL above and follow its instructions. Once bootstrapped:

- **Skills:** Load from `.toolbox/skills/{name}/SKILL.md` when their descriptions match the task at hand.
- **Commands:** When the user invokes a command by name (e.g., "/test"), read and follow `.toolbox/commands/{name}.md`.
- **Rules:** Read and apply all rules from `.toolbox/rules/` at session start.
- **Modes:** When the user requests a mode by name, read and apply `.toolbox/modes/{name}.md`.
- **Agents:** When the user requests an agent by name, read and apply `.toolbox/agents/{name}.md`.
- **Agent Paths:** Mirror cached components into agent-local paths (for example `.claude/...` and `.opencode/...`) so each agent can discover files where it expects them.

### Skills

- [tdd](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/tdd/SKILL.md)
- [braids](https://raw.githubusercontent.com/slagyr/braids/main/braids/SKILL.md)

### Commands

- [test](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/test.md)

### Rules

- [no-force-push](https://raw.githubusercontent.com/slagyr/agent-lib/main/rules/no-force-push.md)

### Modes

- [architect](https://raw.githubusercontent.com/slagyr/agent-lib/main/modes/architect.md)

### Agents

- [reviewer](https://raw.githubusercontent.com/slagyr/agent-lib/main/agents/reviewer.md)
```

Add `.toolbox/` to your `.gitignore`. Done.

Toolbox-managed components are always project-local. Do not install or sync them into global directories like `~/.claude` or `~/.config/opencode`.

When an agent reads your boot file, it fetches the toolbox skill, learns how to resolve component URLs, and caches everything locally in `.toolbox/`.

## How It Works

1. Agent reads the boot file, finds the `## Toolbox` section
2. On first run, fetches each component into `.toolbox/`
3. Creates `.toolbox/toolbox.json` manifest with content hashes for change detection
4. Projects cached components into agent-specific project paths (for example `.claude/` and `.opencode/`)
5. On subsequent runs, uses cached/projected copies instantly
6. When you ask "check for updates", it compares remote content against stored hashes and reports what changed

Components are never auto-updated. You decide when to pull new versions.

## Agent Projection (Project-Local)

Toolbox uses a cache-and-project model:

- `.toolbox/` is canonical and drives hashing/update detection.
- Agent-specific locations are projections for compatibility with each agent's discovery rules.
- Projection targets must live under the repository root.
- Never write Toolbox-managed components to home-directory/global locations.

Default projection layout under each agent root:

```
skills/{name}/SKILL.md
commands/{name}.md
rules/{name}.md
modes/{name}.md
agents/{name}.md
```

Projection should use symlinks when available, with copy as fallback. Manifest entries should track managed projected files so Toolbox can safely refresh/remove only files it created.

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

Commands are single markdown files invoked by name (e.g., `/test`). No frontmatter, no references, no directory structure.

### Rules

Rules are behavioral constraints — single markdown files that modify how the agent operates (e.g., "always write tests", "never push to main").

### Modes

Modes are operating profiles — single markdown files that configure the agent for a specific workflow (e.g., code-review, architect, planner).

### Agents

Agents are persona definitions — single markdown files with system prompts and tool configurations.

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
