---
name: toolbox
description: >-
  Manages component dependencies (skills, commands, rules, modes, agents) for a
  project. Parses component URLs from the project's boot file, fetches them into
  a local .toolbox/ cache, projects them into agent-specific project-local paths
  (.claude/, .grok/, .cursor/, .opencode/), remembers supported agents in the
  manifest, tracks freshness, and updates on demand. Use when a project's boot
  file declares components via URL.
---

# Toolbox — Component Management

Resolve, cache, project, and update component dependencies declared in a project's boot file.

## When This Skill Applies

Respond to these common short prompts (or close variations):

- "Please use [Toolbox](https://raw.githubusercontent.com/slagyr/toolbox/main/SKILL.md) to manage skills and components for this directory." → Bootstrap or load components as described in §1–§6.
- "Please add <link to SKILL.md> to my toolbox." → Add the link to the appropriate subsection in the project's `## Toolbox` section (in AGENTS.md or equivalent), then fetch and integrate the new component.
- "Please update my toolbox." → Check for updates on declared components and apply them (§4–§5).
- "Also support Grok / Cursor / Claude / OpenCode for this project." → Enroll that agent name in `supported_agents` and project into its root (see "Known Agents", §7).
- "Which agents does toolbox support here?" → Report `supported_agents` from the manifest.
- "Stop projecting for <agent>." → Unenroll that name and remove only managed projection paths for that agent (§7).

This skill also applies when you land in a project whose boot file contains a `## Toolbox` section that links to this SKILL.md. In that case, follow the full procedure to make declared components available.

## Core Model: Cache + Projection

Toolbox has two layers:

1. **Canonical cache (`.toolbox/`)** for fetches, hashes, and update detection.
2. **Agent projection roots** for compatibility with agent-specific discovery paths.

Projection roots are always inside the repository (for example `.claude/`, `.grok/`, `.cursor/`, `.opencode/`).

Never install or sync Toolbox-managed components to global locations such as `~/.claude`, `~/.grok`, `~/.cursor`, or `~/.config/opencode`.

## Known Agents

The skill maps each **agent name** to a project-local root and relative layout. The manifest stores only the names in `supported_agents` (a simple string list). Roots and paths are **not** stored in the manifest.

| Name | Root | Relative layout under root |
|------|------|----------------------------|
| `claude-code` | `.claude/` | Default layout (below) |
| `opencode` | `.opencode/` | Default layout |
| `grok` | `.grok/` | Default layout |
| `cursor` | `.cursor/` | Default layout |

**Default relative layout** (all known agents unless noted):

```
skills/{name}/SKILL.md
commands/{name}.md
rules/{name}.md
modes/{name}.md
agents/{name}.md
```

Unknown agent names: warn and skip (do not invent roots).

### Detecting the current agent (session only — do not persist)

Best-effort, ordered:

1. Explicit product/runtime signals when available (Cursor, Grok Build, Claude Code, OpenCode).
2. Otherwise leave detection unknown; still project every name already in `supported_agents` and any known roots that exist on disk.

Detection is used only to **enroll** a newly seen agent and to **prefer** that agent's projection when loading. Do **not** write an `active_agent` field into the manifest.

### Supported agents (durable)

`supported_agents` in `.toolbox/toolbox.json` is a JSON array of agent names, e.g. `["claude-code", "grok", "cursor"]`.

**Enrollment** (add a name if missing, then rewrite the manifest):

1. Detected current agent (when known).
2. Any known agent whose root directory already exists in the repo.
3. Explicit user request (e.g. "also support cursor").

**Unenrollment** only on explicit user request: remove the name from `supported_agents`, delete managed projected files for that agent only (§7), rewrite the manifest.

### Projection target set

On bootstrap, update, or repair:

1. Start with `supported_agents` from the manifest (or `[]` if missing).
2. Add the detected current agent if known.
3. Add any known agent whose root already exists on disk.
4. Dedupe → this is the **target set**.
5. Write the target set back to `supported_agents` (sorted for stable diffs is fine).
6. For **each** name in `supported_agents`, project the full declared component set into that agent's root (create the root if needed).

Never project outside the repo root. Prefer **copy** into projection roots; symlink is allowed when reliable, with copy as fallback.

### Managed projection paths (by convention)

There is **no** `projections` or `managed_files` list in the manifest. A path under an agent root is **Toolbox-managed** only if it is exactly the projected path of a **declared** component for that agent type, e.g.:

- `.grok/skills/tdd/SKILL.md` when skill `tdd` is declared (and any relative files listed for that skill under cache)
- `.cursor/commands/work.md` when command `work` is declared

On update or unenroll:

- May add/overwrite/remove only managed paths for currently declared components (or components just removed from the boot file).
- Never delete or overwrite unrelated files under `.claude/`, `.grok/`, `.cursor/`, or `.opencode/`.

## Component Types

Toolbox manages different types of agent components. Each type has its own subsection under `## Toolbox` in the boot file, its own cache subdirectory, and its own projected path under each agent root.

### Skills

Skills are instruction sets that teach agents how to perform specific tasks. A skill is a directory with a `SKILL.md` entry point and optional reference files.

- **Boot file section:** `### Skills` (under `## Toolbox`)
- **Cache location:** `.toolbox/skills/{name}/SKILL.md`
- **Projection location:** `{agent_root}/skills/{name}/SKILL.md`
- **Reference discovery:** Yes — relative markdown links in `SKILL.md` are fetched automatically.

### Commands

Commands are single-file agent instructions invoked by name (e.g., `/test`, `/deploy`). A command is a single markdown file.

- **Boot file section:** `### Commands` (under `## Toolbox`)
- **Cache location:** `.toolbox/commands/{name}.md`
- **Projection location:** `{agent_root}/commands/{name}.md`
- **Reference discovery:** No — commands are single files.

### Rules

Rules are behavioral constraints that modify how the agent operates (e.g., "always write tests", "never push to main"). A rule is a single markdown file.

- **Boot file section:** `### Rules` (under `## Toolbox`)
- **Cache location:** `.toolbox/rules/{name}.md`
- **Projection location:** `{agent_root}/rules/{name}.md`
- **Reference discovery:** No — rules are single files.

### Modes

Modes are operating profiles that configure the agent's behavior for a specific workflow (e.g., code-review, architect, planner). A mode is a single markdown file.

- **Boot file section:** `### Modes` (under `## Toolbox`)
- **Cache location:** `.toolbox/modes/{name}.md`
- **Projection location:** `{agent_root}/modes/{name}.md`
- **Reference discovery:** No — modes are single files.

### Agents

Agents are full persona definitions with system prompts and tool configurations. An agent is a single markdown file.

- **Boot file section:** `### Agents` (under `## Toolbox`)
- **Cache location:** `.toolbox/agents/{name}.md`
- **Projection location:** `{agent_root}/agents/{name}.md`
- **Reference discovery:** No — agents are single files.

## How Components Are Declared

Components are declared in the project's boot file under a `## Toolbox` section. The boot file is whatever file the agent reads on startup — `AGENTS.md`, `CLAUDE.md`, or any platform-specific equivalent. Each component is a markdown link in a bullet list under its type subsection:

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
- **Agent Paths:** Project cached components into every **supported** agent root (for example `.claude/`, `.grok/`, `.cursor/`, `.opencode/`) so each agent can discover them where it expects. The list of supported agent names is stored in `.toolbox/toolbox.json`.

### Skills

- [tdd](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/tdd/SKILL.md)
- [braids](https://raw.githubusercontent.com/slagyr/braids/main/braids/SKILL.md)

### Commands

- [test](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/test.md)
- [deploy](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/deploy.md)

### Rules

- [no-force-push](https://raw.githubusercontent.com/slagyr/agent-lib/main/rules/no-force-push.md)

### Modes

- [architect](https://raw.githubusercontent.com/slagyr/agent-lib/main/modes/architect.md)

### Agents

- [reviewer](https://raw.githubusercontent.com/slagyr/agent-lib/main/agents/reviewer.md)
```

- The **link text** is the component name.
- The **URL** points to the component's entry point.
- Both `https://` and `file://` URLs are supported.
- Component names must be unique within their type. If duplicates are found, warn the user and use the last declaration.

## Procedures for Common Instructions

### "Please use [Toolbox](https://raw.githubusercontent.com/slagyr/toolbox/main/SKILL.md) to manage skills and components for this directory"

1. Locate the project's boot file (AGENTS.md, CLAUDE.md, or equivalent) and ensure it has a `## Toolbox` section that includes a link to this skill.
2. If `.toolbox/toolbox.json` does not exist, bootstrap the declared components (see §2).
3. If it exists, migrate the manifest if needed (§3.1), load the cached components, ensure `supported_agents` is up to date, and project into **all** supported agent roots (see §6–§7).
4. Proceed with normal work using the projected components (prefer the detected agent's root when loading).

### "Please add <link to SKILL.md> to my toolbox"

1. Determine the component type from context or the link (Skills, Commands, Rules, Modes, or Agents).
2. Locate the project's boot file and find (or create) the `## Toolbox` section with the matching `### <Type>` subsection.
3. Add the link in the correct format (e.g. `- [name](url)`) if it is not already present.
4. Fetch and integrate the new component following the bootstrap steps for that type.
5. Update `.toolbox/toolbox.json` and re-project into **all** `supported_agents`.

### "Please update my toolbox"

Follow the Check for Updates and Update Components procedures below.

### 2. Bootstrap (First Run)

When `.toolbox/toolbox.json` is missing:

1. Create the `.toolbox/` directory in the project root.
2. Parse the `## Toolbox` section of the boot file for component subsections (`### Skills`, `### Commands`, `### Rules`, `### Modes`, `### Agents`). Extract each `[name](url)` pair.
3. For each declared skill (including toolbox itself — use the already-fetched copy rather than re-fetching):
   a. Fetch `SKILL.md` from the skill's URL.
   b. Discover reference files by parsing relative markdown links in `SKILL.md` — patterns like `[text](references/foo.md)` or `[text](some/path.md)`. Only include links to relative paths (not absolute URLs or anchors).
   c. Compute the base URL by removing `SKILL.md` from the skill's URL. Fetch each discovered reference file relative to that base URL.
   d. Write all fetched files into `.toolbox/skills/{name}/`, preserving directory structure.
   e. Compute a SHA-256 hash covering all fetched files (concatenate file contents in sorted order by path, then hash).
4. For each single-file component (commands, rules, modes, agents):
   a. Fetch the file from the URL.
   b. Write it to `.toolbox/{type}/{name}.md`.
   c. Compute the SHA-256 hash of the fetched content.
5. Build `supported_agents` (target set) per **Projection target set** above (detect + existing roots; list starts empty on first run).
6. Project cached components from `.toolbox/` into **each** supported agent's root:
   a. Create needed directories.
   b. Prefer copy (symlink allowed when reliable; fallback to copy).
   c. If a target file already exists and is not Toolbox-managed (by convention), do not overwrite it; warn and skip.
7. Write `.toolbox/toolbox.json` with component entries and `supported_agents` (see §3).
8. Ensure `.toolbox/` is listed in the project's `.gitignore`. If not, add it.

### 3. The Manifest — `.toolbox/toolbox.json`

The manifest tracks cached components, their source URLs, fetched files, content hashes, and which agent names this project supports. Example (hashes and timestamps are illustrative):

```json
{
  "skills": {
    "tdd": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/tdd/SKILL.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "f6e5d4c3b2a1...",
      "files": ["SKILL.md"]
    }
  },
  "commands": {
    "test": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/test.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "d4e5f6a1b2c3...",
      "files": ["test.md"]
    }
  },
  "rules": {
    "no-force-push": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/rules/no-force-push.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "c3d4e5f6a1b2...",
      "files": ["no-force-push.md"]
    }
  },
  "modes": {
    "architect": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/modes/architect.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "e5f6a1b2c3d4...",
      "files": ["architect.md"]
    }
  },
  "agents": {
    "reviewer": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/agents/reviewer.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "a1b2c3d4e5f6...",
      "files": ["reviewer.md"]
    }
  },
  "supported_agents": ["claude-code", "grok", "cursor"]
}
```

**Fields:**

| Field | Description |
|-------|-------------|
| `skills` | Map of skill name → skill entry. |
| `commands` | Map of command name → command entry. |
| `rules` | Map of rule name → rule entry. |
| `modes` | Map of mode name → mode entry. |
| `agents` | Map of agent name → agent entry (persona components, not "supported agents"). |
| `supported_agents` | Array of agent **names** (`claude-code`, `opencode`, `grok`, `cursor`). Roots and layouts are defined by this skill, not stored here. |
| `{type}.{name}.url` | The URL from which the component was fetched. |
| `{type}.{name}.fetched_at` | ISO 8601 timestamp of when the component was last fetched. |
| `{type}.{name}.sha256` | SHA-256 hash covering all of the component's files at fetch time. Computed by concatenating file contents in sorted order by path, then hashing. Used to detect remote changes. |
| `{type}.{name}.files` | List of all files cached for this component, relative to its cache directory. |

Do **not** store `active_agent` or a `projections` map. Projection state is on disk under each agent root; ownership is by path convention.

### 3.1 Manifest migration (older Toolbox)

If `toolbox.json` exists but lacks `supported_agents`:

1. If a legacy `projections` object exists, set `supported_agents` to the unique keys of that object (normalize known names such as `claude-code`, `opencode`).
2. Also add any known agent whose root directory already exists on disk.
3. Remove `projections` and any `active_agent` field if present.
4. Write the updated manifest and re-project all `supported_agents` from cache (no network required).

### 4. Check for Updates

Toolbox detects updates by comparing content, not by time. Each component's `sha256` in the manifest is the hash of all its files at fetch time.

**On session start**, if cached components exist, proceed silently. Do not fetch anything automatically — the cached versions are ready to use. You may enroll newly detected agents, repair missing projections from cache, and re-project without network fetch.

**When the user asks** (e.g., "check for updates", "are my skills up to date?"):

1. For each component in the manifest, fetch all files from the URL.
2. Compute the SHA-256 hash covering all fetched files (same method as bootstrap).
3. Compare to the stored `sha256` in the manifest.
4. Report results:
   ```
   Component updates available:
     Skills:
       - braids (changed)
       - tdd (up to date)
     Commands:
       - test (up to date)
     Rules:
       - no-force-push (changed)
   Update? [y/n]
   ```
5. If the user confirms, proceed with §5 (Update) for the changed components.

### 5. Update Components

When the user asks to update (e.g., "update skills", "refresh components"):

1. Re-parse the boot file for current component declarations. This catches added or removed components.
2. For each declared component:
   a. Re-fetch all files from the URL.
   b. For skills, re-discover and fetch reference files.
   c. Overwrite the cached files.
   d. Update `fetched_at` and `sha256` in the manifest.
3. Remove any cached components that are no longer declared in the boot file.
4. Refresh `supported_agents` (target set algorithm).
5. Re-sync projections for **every** name in `supported_agents`:
   a. Add/update managed projected files for currently declared components.
   b. Remove stale managed projected files that no longer map to declared components.
   c. Never remove or overwrite unmanaged files in agent roots.
6. Write the updated `.toolbox/toolbox.json`.

### 6. Use Components

- **Canonical source:** `.toolbox/` is the source of truth for fetches, hashing, and updates.
- **Agent discovery:** Prefer projected files under the **detected** agent's root when known; otherwise any supported root or the cache.
- **Fallback:** If a projected file is missing but cache exists, restore the projection from `.toolbox/` into **all** `supported_agents` (or at least the detected agent) and continue.
- **Skills:** Load from `{agent_root}/skills/{name}/SKILL.md` (or `.toolbox/skills/{name}/SKILL.md` if projection is temporarily unavailable). References remain under the same relative structure.
- **Commands:** When the user invokes a command by name (e.g., "/test"), read and follow `{agent_root}/commands/{name}.md` (or cache).
- **Rules:** Read and apply all rules from `{agent_root}/rules/` (or `.toolbox/rules/`) at session start. Rules are always active.
- **Modes:** When the user requests a mode by name, read and apply `{agent_root}/modes/{name}.md` (or cache).
- **Agents:** When the user requests an agent by name, read and apply `{agent_root}/agents/{name}.md` (or cache).

### 7. Enroll / Unenroll Agents

**Enroll** (add to `supported_agents` if missing):

1. Resolve the agent name (`claude-code`, `opencode`, `grok`, `cursor`).
2. Append to `supported_agents`, write manifest.
3. Project all declared components into that agent's root from cache.

**Unenroll** (explicit user request only):

1. Remove the name from `supported_agents`.
2. Delete only **managed** projected paths for that agent (paths that match declared-or-just-removed component projections).
3. Write the manifest. Do not delete the agent root wholesale if it contains unmanaged files.

## URL Schemes

### `https://`

Fetch via HTTP GET. This is the primary use case for portable, published components.

For components hosted on GitHub, use `raw.githubusercontent.com` URLs:
```
https://raw.githubusercontent.com/{owner}/{repo}/{branch}/path/to/file.md
```

To pin a specific version, use a commit SHA instead of a branch name:
```
https://raw.githubusercontent.com/{owner}/{repo}/{sha}/path/to/file.md
```

**Private repos:** `raw.githubusercontent.com` does not serve files from private repositories without authentication — an anonymous fetch returns 404, not 401. Do not put an access token in a boot file that is committed. For private components, keep the source checked out inside the project and declare it with a **relative `file://` URL** (below).

### `file://`

Copy from the local filesystem. Useful for:
- Components under active development
- Private components that won't be published
- Migration from filesystem-based references

**Absolute** — resolves exactly as written:
```markdown
- [braids](file:///Users/micah/Projects/braids/braids/SKILL.md)
```

**Relative** — a path beginning with `./` or `../` resolves against the **directory containing the boot file**:
```markdown
- [tdd](file://./private-lib/skills/tdd/SKILL.md)
```

Relative file URLs are the portable form. They travel across machines and teammates because they name a location *within* the project rather than a location on one person's disk. Use them when a private component repo is checked out inside the project — the checkout must be part of the project's documented setup, or the URL will not resolve on a fresh machine.

Resolution rules:

1. Resolve the path against the boot file's directory, then normalize it.
2. Reject any path that escapes the project root after normalization, and warn. A component source may sit anywhere inside the project, but never above it.
3. Treat a missing target as a fetch failure — warn and skip (§Error Handling). Do not fall back to a stale cache silently.

**Note:** Absolute `file://` URLs are not portable across machines. Prefer the relative form, or `https://` for components that need to work everywhere.

## Reference Discovery

When fetching a skill's `SKILL.md`, parse it for relative markdown links to discover supporting files.

**Match patterns:**
- `[text](references/foo.md)` — standard markdown link with relative path
- `[text](some/path.md)` — any relative path (no scheme, no leading `/`)

**Exclude:**
- Absolute URLs (`https://...`, `http://...`, `file://...`)
- Anchor links (`#section`)

**Resolve:** Given a skill URL like `https://example.com/skills/solid/SKILL.md`, the base URL is `https://example.com/skills/solid/`. A reference `references/tdd.md` resolves to `https://example.com/skills/solid/references/tdd.md`.

For `file://` URLs, the same logic applies using filesystem paths.

Reference discovery applies only to skills. All other component types are single files with no references.

## Error Handling

- **Fetch failure (single component):** If a component's URL returns an error (404, timeout, network unavailable), warn the user and skip that component. Do not block the entire bootstrap or update process.
- **Fetch failure (reference file):** If a reference file fails to fetch, warn the user and continue. The skill may still be usable without it.
- **No `## Toolbox` section:** If the boot file has no `## Toolbox` section, toolbox does not apply. Do nothing.
- **Invalid `file://` path:** If a `file://` path does not exist, treat it as a fetch failure — warn and skip. When a *relative* file URL misses, say which checkout is absent and that the project's setup is expected to provide it — a missing sibling checkout is the likely cause, not a typo.
- **`file://` path escapes the project:** If a relative file URL normalizes to a path above the project root, reject and warn. Never read component sources from outside the project.
- **Unknown agent name:** Warn and skip; do not invent a root.
- **Projection root outside repo:** Reject and warn. Never write to global/home paths.
- **Projection conflict:** If a destination file exists and is not Toolbox-managed, do not overwrite; warn and skip that file.
- **Projection failure:** If symlink creation fails, fallback to copy. If copy also fails, warn and continue with remaining files.
- **General rule:** Never silently swallow errors. Always inform the user what failed and why.

## Limitations

- **No component dependencies.** Toolbox treats each component as independent. If skill A requires skill B, the skill author should note this in their `SKILL.md` description so that projects declare both explicitly.
- **Built-in agents only.** Automatic projection targets `claude-code`, `opencode`, `grok`, and `cursor`. Other products need a skill update (or remain unsupported).
- **Same components for every supported agent.** There is no per-agent component subset in the manifest.
- **Relative `file://` sources are only as reliable as project setup.** Toolbox does not clone them. If a declared source checkout is missing, those components are simply unavailable until the project's own setup provides it.
