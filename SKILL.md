---
name: toolbox
description: >-
  Manages component dependencies (skills, commands, rules, modes, agents) for a
  project. Parses component URLs from the project's boot file, fetches them into
  a local .toolbox/ cache, projects them into agent-specific project-local paths
  (.claude/, .grok/, .cursor/, .opencode/), remembers supported agents in the
  manifest, tracks freshness, and updates on demand — never overwriting local
  edits to managed files. Use when a project's boot file declares components
  via URL.
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

## The Prime Directive: Never Overwrite Bytes You Didn't Write

Agents and humans sometimes edit a Toolbox-managed file in place — a local fix to a skill, a project-specific tweak to a command. Those edits must never be destroyed by a refresh.

Before **any** overwrite — cache update or projection sync — hash the destination file and compare it to the manifest's recorded per-file hash (§3):

- **Hash matches** → the file is exactly what Toolbox last wrote. Safe to replace.
- **File missing** → restore it from cache (or fetch).
- **Hash differs** → the file was locally modified. It is **protected**: never silently replace or delete it. Follow §5.1 (Local Modifications).

This rule applies at both layers — the `.toolbox/` cache and every agent projection root — and has no exceptions. A file whose content Toolbox cannot account for is someone else's work, even when it sits at a managed path. Remember that `.toolbox/` and projection roots are typically gitignored: a clobbered edit there has no git history to recover from.

**Last line of defense:** whenever Toolbox is about to replace or delete a file that fails the hash check (e.g. the user explicitly confirms an overwrite), first copy the existing file to `.toolbox/backup/<YYYY-MM-DD>/<type>/<name>/<relative-path>`. Nothing is ever unrecoverable.

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
   e. Compute a SHA-256 hash of **each** fetched file (these become the `files` map, §3). The component-level `sha256` is the SHA-256 of the lines `{path}:{file-hash}` sorted by path and joined with newlines — so it changes when any file's content changes, a file is added/removed, or a file is renamed.
4. For each single-file component (commands, rules, modes, agents):
   a. Fetch the file from the URL.
   b. Write it to `.toolbox/{type}/{name}.md`.
   c. Compute the SHA-256 hash of the fetched content (recorded per file; component `sha256` derived the same way as for skills).
5. Build `supported_agents` (target set) per **Projection target set** above (detect + existing roots; list starts empty on first run).
6. Project cached components from `.toolbox/` into **each** supported agent's root:
   a. Create needed directories.
   b. Prefer copy (symlink allowed when reliable; fallback to copy).
   c. If a target file already exists, apply the Prime Directive: replace it only when its content is byte-identical to the cached file being projected (no manifest exists yet at bootstrap, so compare content directly). Otherwise it is protected — leave it, warn, and report it as a local modification (§5.1).
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
      "files": {
        "SKILL.md": "1a2b3c4d5e6f...",
        "references/details.md": "6f5e4d3c2b1a..."
      }
    }
  },
  "commands": {
    "test": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/test.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "d4e5f6a1b2c3...",
      "files": {"test.md": "2b3c4d5e6f1a..."}
    }
  },
  "rules": {
    "no-force-push": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/rules/no-force-push.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "c3d4e5f6a1b2...",
      "files": {"no-force-push.md": "3c4d5e6f1a2b..."}
    }
  },
  "modes": {
    "architect": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/modes/architect.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "e5f6a1b2c3d4...",
      "files": {"architect.md": "4d5e6f1a2b3c..."}
    }
  },
  "agents": {
    "reviewer": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/agents/reviewer.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "a1b2c3d4e5f6...",
      "files": {"reviewer.md": "5e6f1a2b3c4d..."}
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
| `{type}.{name}.sha256` | Component-level hash: SHA-256 of the lines `{path}:{file-hash}` sorted by path, joined with newlines. Used to detect remote changes cheaply. |
| `{type}.{name}.files` | Map of file path (relative to the component's cache directory) → SHA-256 of that file's content at fetch time. These per-file hashes are what the Prime Directive checks before overwriting anything. |

Do **not** store `active_agent` or a `projections` map. Projection state is on disk under each agent root; ownership is by path convention.

### 3.1 Manifest migration (older Toolbox)

If `toolbox.json` exists but lacks `supported_agents`:

1. If a legacy `projections` object exists, set `supported_agents` to the unique keys of that object (normalize known names such as `claude-code`, `opencode`).
2. Also add any known agent whose root directory already exists on disk.
3. Remove `projections` and any `active_agent` field if present.
4. Write the updated manifest and re-project all `supported_agents` from cache (no network required).

If any component's `files` is a **list** (older Toolbox) rather than a map:

1. Hash each listed file as it exists in `.toolbox/` cache and rebuild `files` as a path → hash map. No network required.
2. Caveat: if the cache itself was hand-edited before this migration, those edits are baked in as the recorded baseline. That is the safe direction — the edit is treated as canonical rather than silently reverted on the next update.

### 4. Check for Updates

Toolbox detects updates by comparing content, not by time. Each component's `sha256` in the manifest is the hash of all its files at fetch time.

**On session start**, if cached components exist, proceed silently. Do not fetch anything automatically — the cached versions are ready to use. You may enroll newly detected agents, repair missing projections from cache, and re-project without network fetch.

**When the user asks** (e.g., "check for updates", "are my skills up to date?"):

1. For each component in the manifest, fetch all files from the URL.
2. Compute the component hash from the fetched files (same method as bootstrap) and compare to the stored `sha256` — this detects **remote changes**.
3. Hash the component's files on disk — in cache *and* in each supported projection root — against the manifest's `files` map. Any mismatch is a **local modification**.
4. Report both dimensions:
   ```
   Component status:
     Skills:
       - braids (changed upstream)
       - tdd (up to date; locally modified in .claude/ — will be preserved)
     Commands:
       - test (up to date)
     Rules:
       - no-force-push (changed upstream AND locally modified — needs merge, see below)
   Update? [y/n]
   ```
5. If the user confirms, proceed with §5 (Update) for the changed components. Components that are locally modified follow §5.1 — they are never silently overwritten.

### 5. Update Components

When the user asks to update (e.g., "update skills", "refresh components"):

1. Re-parse the boot file for current component declarations. This catches added or removed components.
2. For each declared component:
   a. Re-fetch all files from the URL.
   b. For skills, re-discover and fetch reference files.
   c. **Prime Directive check:** hash each cached file against the manifest's `files` map. Clean files are overwritten with the fetched content. Modified files go through §5.1 instead — do not overwrite them here.
   d. Update `fetched_at`, `sha256`, and the `files` map in the manifest for every file actually written.
3. Remove any cached components that are no longer declared in the boot file — but a no-longer-declared file that fails the hash check is protected: back it up to `.toolbox/backup/<date>/` before removal, and say so.
4. Refresh `supported_agents` (target set algorithm).
5. Re-sync projections for **every** name in `supported_agents`:
   a. **Prime Directive check first:** hash each existing projected file against the manifest. Clean or missing → copy from cache. Modified → §5.1; leave the file in place.
   b. Remove stale managed projected files that no longer map to declared components (same backup-if-modified rule as step 3).
   c. Never remove or overwrite unmanaged files in agent roots.
6. Write the updated `.toolbox/toolbox.json`.
7. End with a summary that names every file that was preserved, merged, staged, or backed up. Silence about a protected file is a bug.

### 5.1 Local Modifications: Detect, Merge, Preserve

When a managed file fails the hash check, someone edited it deliberately. Never revert it as a side effect of an update. You have all three versions available, so act as the merge tool:

- **base** — the previous upstream version. Its hash is in the manifest; its content is the cached copy (when the edit is in a projection) or must be re-derivable from the pinned URL. If base content cannot be recovered, fall back to a two-way comparison and be conservative.
- **ours** — the locally edited file on disk.
- **theirs** — the freshly fetched remote content.

Procedure:

1. **Compute the local diff** (base → ours) and show the user a short summary of what was changed locally.
2. **If upstream did not change** (base = theirs): keep ours untouched. Just report the drift.
3. **If upstream changed:** perform a three-way merge — apply the local edits on top of the new upstream content, semantically (you are an agent reading markdown, not `patch(1)`; preserve the *intent* of the local edit even if surrounding text moved).
   - Present the merged result (or a diff) and write it only on confirmation.
   - Update the manifest hash to the **merged** content so the next update sees it as the new local baseline... and it will be flagged as locally-modified again next time upstream moves, which is correct.
   - If the local edit and the upstream change genuinely conflict, keep ours in place and write theirs to `.toolbox/incoming/{type}/{name}/{path}` so nothing is lost while the user decides.
4. **Back up before any write** that replaces modified content: copy the pre-merge file to `.toolbox/backup/<YYYY-MM-DD>/...` (Prime Directive, last line of defense).
5. **Offer the two exits from permanent drift** — a merged-forward local edit is still a fork, and forks rot:
   - **Upstream it:** the component URL names its source repo. Offer to turn the local diff into a commit or PR against that repo so the edit becomes everyone's.
   - **Take ownership:** switch the boot-file declaration to the project's own copy (a relative `file://` URL or a fork's `https://` URL). The component is then declared as the project's; upstream refreshes stop touching it.

Every drifted file ends each update in exactly one of these states: **kept** (upstream unchanged), **merged forward**, **kept + incoming staged** (conflict), **upstreamed**, or **owned**. Never silently reverted; never silently forked forever.

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
- **Projection conflict:** If a destination file exists and its hash does not match the manifest (or, at bootstrap, its content differs from cache), it is protected — do not overwrite; warn and route through §5.1.
- **Locally modified file during update:** Never overwrite or delete it in passing. Follow §5.1: keep, merge with confirmation, or stage the incoming version to `.toolbox/incoming/`; back up before any replacing write.
- **Projection failure:** If symlink creation fails, fallback to copy. If copy also fails, warn and continue with remaining files. (Note: symlinked projections make the cache and projection one file — an edit through the symlink edits the cache. Prefer copy where local edits are likely.)
- **General rule:** Never silently swallow errors. Always inform the user what failed and why.

## Limitations

- **No component dependencies.** Toolbox treats each component as independent. If skill A requires skill B, the skill author should note this in their `SKILL.md` description so that projects declare both explicitly.
- **Built-in agents only.** Automatic projection targets `claude-code`, `opencode`, `grok`, and `cursor`. Other products need a skill update (or remain unsupported).
- **Same components for every supported agent.** There is no per-agent component subset in the manifest.
- **Relative `file://` sources are only as reliable as project setup.** Toolbox does not clone them. If a declared source checkout is missing, those components are simply unavailable until the project's own setup provides it.
