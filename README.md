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

4. (Optional) Enroll another agent so its discovery tree stays in sync:
```txt
Also support Grok for this project.
Also support Cursor for this project.
```

## Introduction

**The problem:** Agent components — skills, commands, and other reusable instructions — often live at machine-specific paths (`~/.config/opencode/skills/`, `~/.claude/skills/`, `~/.cursor/skills/`) or agent-specific project paths. If files are not in the exact place a given agent expects, discovery fails.

**The solution:** Declare components by URL in your project's boot file (`AGENTS.md`, `CLAUDE.md`, or whatever your agent reads on startup). Toolbox fetches and caches them in `.toolbox/` (source of truth), then projects them into **every supported agent**'s project-local tree (`.claude/`, `.grok/`, `.cursor/`, `.opencode/`, `.codex/`). The manifest remembers **which agent names** this project supports (`supported_agents`); the skill knows the root and layout for each name. No global installation required.

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
- **Agent Paths:** Project cached components into every supported agent root (`.claude/`, `.grok/`, `.cursor/`, `.opencode/`, `.codex/`) so each product can discover files where it expects them.

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

## Shared + Personal (`*.TOOLBOX.md`)

The boot file declares what the whole team uses. Any file named `TOOLBOX.md` or `*.TOOLBOX.md` at the project root adds to it — Toolbox discovers them automatically. No include syntax, no registration, no edit to the shared file.

```markdown
<!-- ratchet.TOOLBOX.md -->
### Skills

- [mutation-testing](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/clj-mutate/SKILL.md)
```

A discovered file uses the same subsections as the boot file (`### Skills`, `### Commands`, …). This is how an individual — or an individual agent in a shared repo — declares its own tools: drop `micah.TOOLBOX.md` next to the boot file and they exist. Commit it when each individual owns their own file; gitignore it if it's machine-personal.

Merging is a union. On a name conflict a `*.TOOLBOX.md` beats the boot file, so a personal file can pin a different version of a shared component. Every discovered file and every override is reported, never silent, and the manifest records which file declared each component.

Toolbox-managed components are always project-local. Do not install or sync them into global directories like `~/.claude` or `~/.config/opencode`.

When an agent reads your boot file, it fetches the toolbox skill, learns how to resolve component URLs, and caches everything locally in `.toolbox/`.

## How It Works

1. Agent reads the boot file, finds the `## Toolbox` section
2. On first run, fetches each component into `.toolbox/`
3. Creates `.toolbox/toolbox.json` with content hashes and a `supported_agents` name list
4. Projects cached components into **each** supported agent root (create roots as needed)
5. On later sessions, enrolls newly detected agents, keeps all supported roots in sync from cache
6. When you ask "check for updates", it compares remote content against stored hashes and reports what changed

Components are never auto-updated. You decide when to pull new versions.

## Agent Projection (Project-Local)

Toolbox uses a cache-and-project model:

- `.toolbox/` is canonical and drives hashing/update detection.
- Agent-specific locations are projections for each product's discovery rules.
- Projection targets must live under the repository root.
- Never write Toolbox-managed components to home-directory/global locations.

### Built-in agent names

| Name | Project root |
|------|----------------|
| `claude-code` | `.claude/` |
| `opencode` | `.opencode/` |
| `grok` | `.grok/` |
| `cursor` | `.cursor/` |
| `codex` | `.codex/` |

Default layout under each root:

```
skills/{name}/SKILL.md
commands/{name}.md
rules/{name}.md
modes/{name}.md
agents/{name}.md
```

### Supported agents

The manifest stores only:

```json
"supported_agents": ["claude-code", "grok", "cursor"]
```

The skill maps each name to a root and layout. There is no `active_agent` field and no `projections` map — roots and file ownership follow the skill; projected files on disk are the projection state.

**Enrollment:** detect the current product when possible, notice existing agent roots, or honor an explicit "support Cursor/Grok" request. Once enrolled, every update re-projects that agent.

**Loading this session:** prefer the detected agent's tree when known; fall back to `.toolbox/`.

Prefer **copy** into projection roots (symlink makes cache and projection the same file, which defeats local-edit detection). Only overwrite or remove paths that match declared component projections (managed by convention) — and even those only when their content hash proves Toolbox wrote them (see Non-Destructive by Design).

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

For components under active development, use an absolute path:

```markdown
- [my-skill](file:///Users/me/Projects/my-skill/SKILL.md)
- [my-cmd](file:///Users/me/Projects/my-commands/my-cmd.md)
```

Absolute `file://` URLs aren't portable across machines — switch to `https://` when you publish.

### Private repos

`raw.githubusercontent.com` returns 404 for private repositories, and an access token must never be committed in a boot file. Instead, check the private component repo out **inside** your project and declare it with a relative `file://` URL — a path starting with `./` or `../`, resolved against the boot file's directory:

```markdown
- [tdd](file://./private-lib/skills/tdd/SKILL.md)
- [deploy](file://./private-lib/commands/deploy.md)
```

This form is portable: it names a location inside the project, so it resolves the same way for every teammate. Toolbox does not clone the source — make that checkout part of your project's documented setup, or the URLs won't resolve on a fresh machine. Paths that normalize above the project root are rejected.

### Pinned versions

Use a commit SHA instead of a branch name to lock a component to a specific version:

```markdown
- [tdd](https://raw.githubusercontent.com/slagyr/agent-lib/a1b2c3d/skills/tdd/SKILL.md)
```

## Update Detection

Toolbox stores a SHA-256 hash of each of a component's files at fetch time (plus a component-level hash for cheap comparison). When you ask to check for updates, it re-fetches and compares hashes — reporting components whose content changed upstream **and** files that were modified locally. No polling, no timers, no wasted fetches.

Status reports are honest about what was verified. An `https://` fetch is authoritative, so those components can be called **up to date**. A `file://` source is a checkout — itself a cache of something upstream — so those report **matches source**, and when the source is a git checkout with an upstream, Toolbox fetches (never pulls) and tells you how far behind origin it is, offering the pull instead of doing it.

## Non-Destructive by Design

Agents and humans sometimes edit a managed file in place — a local fix to a skill, a project-specific tweak to a command. Toolbox never destroys those edits. The rule is simple: **never overwrite bytes it didn't write.**

Before any overwrite, Toolbox hashes the file on disk against the manifest. A file that matches is Toolbox's own and is safe to replace. A file that differs was edited deliberately and is **protected**:

- If upstream didn't change, the local edit is simply kept (and reported).
- If upstream changed too, the agent performs a **three-way merge** — your edits carried forward onto the new upstream content — shown to you before anything is written. On a genuine conflict, your version stays in place and the new upstream version is staged to `.toolbox/incoming/` for you to reconcile.
- Any write that replaces modified content backs the original up to `.toolbox/backup/<date>/` first (kept for 30 days; pruning is always reported). Nothing is ever silently unrecoverable.

Every update ends with each drifted file in a deliberate state: kept, merged forward, staged for review — or, to end the drift, **upstreamed** (Toolbox offers to turn your local diff into a PR against the component's source repo) or **owned** (switch the declaration to your own copy via a relative `file://` URL, so upstream refreshes stop touching it).
