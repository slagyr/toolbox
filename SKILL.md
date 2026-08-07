---
name: toolbox
description: >-
  Manages component dependencies (skills, commands, rules, modes, agents) for a
  project. Parses component URLs from the project's boot file — plus any
  *.TOOLBOX.md files discovered in the project — fetches them into
  a local .toolbox/ cache, projects them into agent-specific project-local paths
  (.claude/, .grok/, .cursor/, .opencode/, .codex/), remembers supported agents in the
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

## Fast Path: babashka

Everything deterministic in this spec — discovery, parsing, fetching, hashing, guarded writes, projection, backups, pruning — is implemented as `.clj` code in this repo, runnable under babashka or the JVM. **When `bb` is on PATH, use it instead of performing the procedures by hand**: one command replaces dozens of tool calls, and it cannot get a hash comparison wrong.

1. Ensure a checkout of this repo at `.toolbox/bin/toolbox` (bookkeeping, never a component):
   `git clone https://github.com/slagyr/toolbox .toolbox/bin/toolbox` — or `git -C .toolbox/bin/toolbox pull --ff-only` to refresh.
2. Run operations from the project root:
   ```sh
   bb --config .toolbox/bin/toolbox/bb.edn toolbox bootstrap   # first run
   bb --config .toolbox/bin/toolbox/bb.edn toolbox status      # check updates + drift + freshness
   bb --config .toolbox/bin/toolbox/bb.edn toolbox update      # apply clean changes
   bb --config .toolbox/bin/toolbox/bb.edn toolbox enroll codex
   bb --config .toolbox/bin/toolbox/bb.edn toolbox prune
   ```
3. Output is JSON. The script applies every mechanical change and **stops at every judgment call**: files needing human/agent decisions are listed under `"attention"`. Handling those is your job — the §5.1 three-way merge, the upstream-a-PR offer, narrating overrides and removals to the user. The script never merges, never overwrites a protected file, and never deletes without a backup.
4. No `bb` on the machine → follow the manual procedures below; they are the normative spec. The code implements this spec — when they disagree, the spec wins and the divergence is a bug (fix the code, not the spec).

Division of labor, in one line: **the script does the bookkeeping; the agent does the judgment.**

## Core Model: Cache + Projection

Toolbox has two layers:

1. **Canonical cache (`.toolbox/`)** for fetches, hashes, and update detection.
2. **Agent projection roots** for compatibility with agent-specific discovery paths.

Projection roots are always inside the repository (for example `.claude/`, `.grok/`, `.cursor/`, `.opencode/`, `.codex/`).

Never install or sync Toolbox-managed components to global locations such as `~/.claude`, `~/.grok`, `~/.cursor`, `~/.codex`, or `~/.config/opencode`.

## The Prime Directive: Never Overwrite Bytes You Didn't Write

Agents and humans sometimes edit a Toolbox-managed file in place — a local fix to a skill, a project-specific tweak to a command. Those edits must never be destroyed by a refresh.

Before **any** overwrite — cache update or projection sync — hash the destination file and compare it to the manifest's recorded per-file hash (§3):

- **Hash matches** → the file is exactly what Toolbox last wrote. Safe to replace.
- **File missing** → restore it from cache (or fetch).
- **Hash differs** → the file was locally modified. It is **protected**: never silently replace or delete it. Follow §5.1 (Local Modifications).

This rule applies at both layers — the `.toolbox/` cache and every agent projection root — and has no exceptions. A file whose content Toolbox cannot account for is someone else's work, even when it sits at a managed path. Remember that `.toolbox/` and projection roots are typically gitignored: a clobbered edit there has no git history to recover from.

**Last line of defense:** whenever Toolbox is about to replace or delete a file that fails the hash check (e.g. the user explicitly confirms an overwrite), first copy the existing file to `.toolbox/backup/<YYYY-MM-DD>/<type>/<name>/<relative-path>`.

**Backup retention:** Toolbox-created backups are pruned after **30 days**, and pruning always reports what it removed — it never runs silently. The guarantee is therefore precise: nothing is ever *silently* unrecoverable, and anything Toolbox replaced stays recoverable for at least 30 days.

**Bookkeeping invariant:** only `.toolbox/{skills,commands,rules,modes,agents}/` hold components. Everything else under `.toolbox/` — `toolbox.json`, `backup/`, `incoming/`, and anything added later — is Toolbox's own bookkeeping: never treated as a component, and never removed by cleanup of undeclared components.

## Known Agents

The skill maps each **agent name** to a project-local root and relative layout. The manifest stores only the names in `supported_agents` (a simple string list). Roots and paths are **not** stored in the manifest.

| Name | Root | Relative layout under root |
|------|------|----------------------------|
| `claude-code` | `.claude/` | Default layout (below) |
| `opencode` | `.opencode/` | Default layout |
| `grok` | `.grok/` | Default layout |
| `cursor` | `.cursor/` | Default layout |
| `codex` | `.codex/` | Default layout |

**Default relative layout** (all known agents unless noted):

```
skills/{name}/SKILL.md
commands/{name}.md
rules/{name}.md
modes/{name}.md
agents/{name}.md
```

Unknown agent names: warn and skip (do not invent roots).

Note: `codex` has no native project-local auto-discovery — Codex reads the boot file and follows its `## Toolbox` loading instructions directly. The `.codex/` projection is an organized, browsable mirror for teams that want one, not a discovery requirement.

### Detecting the current agent (session only — do not persist)

Best-effort, ordered:

1. Explicit product/runtime signals when available (Cursor, Grok Build, Claude Code, OpenCode, Codex).
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
- Never delete or overwrite unrelated files under `.claude/`, `.grok/`, `.cursor/`, `.opencode/`, or `.codex/`.

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
- **Agent Paths:** Project cached components into every **supported** agent root (for example `.claude/`, `.grok/`, `.cursor/`, `.opencode/`, `.codex/`) so each agent can discover them where it expects. The list of supported agent names is stored in `.toolbox/toolbox.json`.

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
- Component names must be unique within their type **within one file** (if duplicated, warn and use the last declaration). Across files, conflicts are resolved by precedence (§Composition).

## Composition: `*.TOOLBOX.md` Files

A project's declarations can span multiple files. The boot file's `## Toolbox` section declares the shared set everyone uses; any **`TOOLBOX.md` or `*.TOOLBOX.md`** file at the **project root** adds to it. An individual (or an individual agent in a shared repo) drops `micah.TOOLBOX.md` next to the boot file and those tools are declared. No edit to the shared boot file, no include syntax, no registration anywhere.

### Discovery

- When assembling the declaration set (bootstrap and update), glob the **project root only** — not subdirectories — for `TOOLBOX.md` and `*.TOOLBOX.md`.
- **Report every discovered file** each time the set is assembled. Discovery must never be silent — an unnoticed declaration file is an unnoticed source of tools.

### Format

A `*.TOOLBOX.md` file uses the same subsections as the boot file — `### Skills`, `### Commands`, `### Rules`, `### Modes`, `### Agents` — with or without a surrounding `## Toolbox` header. Component URLs resolve exactly as in the boot file: `https://` as written, relative `file://` against the directory of the file that declares them.

### Merge semantics

- The set is the **union** of all declarations.
- On a name conflict within a type, any `*.TOOLBOX.md` beats the boot file (personal beats shared); among discovered files, the lexicographically later filename wins. This lets an individual not only add tools but pin a different version of a shared one.
- Every override is **reported**, never silent: `tdd: micah.TOOLBOX.md overrides AGENTS.md`.
- Each component's manifest entry records `declared_in` — the file whose declaration won — and every declaration file's content hash is recorded in `declaration_files` (§3) so updates can tell when the set itself changed.

### Safety

- If a discovered file exists but cannot be read, warn, continue with the files that did load, and **skip removal-cleanup entirely for that run** — a component must never be swept because the file declaring it was temporarily unreadable.
- A declaration file that was **deleted** is a real removal: components declared only there are no longer declared, and normal cleanup (with its protection and backup rules) applies.
- Declaration files are read only at bootstrap and update, like everything else. The per-session token footprint does not grow with the number of files.

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
2. Assemble the **declaration set** (§Composition): parse the boot file's `## Toolbox` section and every discovered `TOOLBOX.md` / `*.TOOLBOX.md` file, reporting what was discovered. Extract each `[name](url)` pair from the component subsections (`### Skills`, `### Commands`, `### Rules`, `### Modes`, `### Agents`), merging by precedence and reporting overrides.
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
7. Write `.toolbox/toolbox.json` with component entries, `declaration_files`, and `supported_agents` (see §3).
8. Ensure `.toolbox/` is listed in the project's `.gitignore`. If not, add it. A `*.TOOLBOX.md` that is personal to one machine can be gitignored, but in repos where each user or agent owns their own directory (e.g. per-agent homes), committing them is the normal case; don't force either way.

### 3. The Manifest — `.toolbox/toolbox.json`

The manifest tracks cached components, their source URLs, fetched files, content hashes, and which agent names this project supports. Example (hashes and timestamps are illustrative):

```json
{
  "declaration_files": {
    "AGENTS.md": "9f8e7d6c5b4a...",
    "micah.TOOLBOX.md": "8e7d6c5b4a9f...",
    "ratchet.TOOLBOX.md": "7d6c5b4a9f8e..."
  },
  "skills": {
    "tdd": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/tdd/SKILL.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "f6e5d4c3b2a1...",
      "declared_in": "AGENTS.md",
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
      "declared_in": "micah.TOOLBOX.md",
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
| `supported_agents` | Array of agent **names** (`claude-code`, `opencode`, `grok`, `cursor`, `codex`). Roots and layouts are defined by this skill, not stored here. |
| `declaration_files` | Map of declaration file (project-relative path or URL) → SHA-256 of its content when the declaration set was last assembled. Detects changes to the set itself. |
| `{type}.{name}.declared_in` | The declaration file whose entry won for this component (§Composition merge semantics). Lets status reports group by source and removal-cleanup check the right files. |
| `{type}.{name}.url` | The URL from which the component was fetched. |
| `{type}.{name}.fetched_at` | ISO 8601 timestamp of when the component was last fetched. |
| `{type}.{name}.sha256` | Component-level hash: SHA-256 of the lines `{path}:{file-hash}` sorted by path, joined with newlines. Used to detect remote changes cheaply. |
| `{type}.{name}.files` | Map of file path (relative to the component's cache directory) → SHA-256 of that file's content at fetch time. These per-file hashes are what the Prime Directive checks before overwriting anything. |
| `{type}.{name}.source_rev` | Optional; recorded only for `file://` sources that resolve inside a git repository. The source checkout's HEAD commit at fetch time. This is what makes the previous upstream version recoverable for merging (§5.1) — walk up from the source path to the repo root, then `git show <source_rev>:<repo-relative-path>`. |

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

If the manifest lacks `declaration_files` (pre-composition Toolbox): assemble the declaration set (§Composition), record the file hashes, and stamp each component's `declared_in` from the merge. Components in the manifest but in no declaration file are reported — not removed — on this first migration run.

### 4. Check for Updates

Toolbox detects updates by comparing content, not by time. Each component's `sha256` in the manifest is the hash of all its files at fetch time.

**On session start**, if cached components exist, proceed silently. Do not fetch anything automatically — the cached versions are ready to use. You may enroll newly detected agents, repair missing projections from cache, and re-project without network fetch.

**When the user asks** (e.g., "check for updates", "are my skills up to date?"):

1. Re-assemble the declaration set (§Composition) — re-discover and re-read all `*.TOOLBOX.md` files plus the boot file — so the check covers what is *currently* declared, and note any declaration files that appeared, disappeared, or changed. Then for each component in the set, fetch all files from the URL.
2. Compute the component hash from the fetched files (same method as bootstrap) and compare to the stored `sha256` — this detects **remote changes**.
3. Hash the component's files on disk — in cache *and* in each supported projection root — against the manifest's `files` map. Any mismatch is a **local modification**.
4. **`file://` sources — establish freshness honestly.** An `https://` fetch is authoritative: the response *is* the latest, so "up to date" is a claim Toolbox can vouch for. A `file://` URL pointing into a checkout is itself a cache of something upstream — comparing against it only proves the cache matches a directory on this disk. **Never report a `file://` component as "up to date"; reserve that phrase for sources Toolbox actually verified.** For a `file://` source, establish what can be known:
   a. Walk up from the resolved source path looking for an enclosing `.git`.
   b. If a repo with an upstream is found, `git fetch` it — best-effort with a short timeout. Fetch only updates remote-tracking refs, so it is safe to run unprompted, but it can hang on a credential prompt in unattended contexts; on any failure or timeout, degrade to "freshness unknown".
   c. Count commits behind with `git rev-list --count HEAD..@{u}`. (Direction matters: `@{u}..HEAD` counts commits *ahead* — local commits not yet pushed — which is a different report.)
   d. Also check whether the source is **dirty at the declared path**. Uncommitted changes there are normal for components under active development, but they are a different fact than "behind origin" — report them distinctly.
   e. Behind → report `matches source; source is N commits behind <upstream>` and **offer** to pull. Never pull unprompted: the checkout is the user's repo — possibly dirty, on a branch, or carrying local commits — not Toolbox's cache.
   f. No repo, no upstream, or fetch failed → report `matches source; source freshness unknown`, never "up to date".
5. Report both dimensions, with the honest vocabulary:
   ```
   Component status:
     Skills:
       - braids (changed upstream)
       - tdd (up to date; locally modified in .claude/ — will be preserved)
     Commands:
       - test (up to date)
       - deploy (matches source; source is 3 commits behind origin/main — pull?)
     Rules:
       - no-force-push (changed upstream AND locally modified — needs merge, see below)
   Update? [y/n]
   ```
6. If the user confirms, proceed with §5 (Update) for the changed components. Components that are locally modified follow §5.1 — they are never silently overwritten.

### 5. Update Components

When the user asks to update (e.g., "update skills", "refresh components"):

1. Re-assemble the declaration set (§Composition): the boot file plus all discovered `*.TOOLBOX.md` files. This catches added, removed, and overridden components. Update `declaration_files` in the manifest, and set each component's `declared_in` per the merge.
2. For each declared component:
   a. Re-fetch all files from the URL.
   b. For skills, re-discover and fetch reference files.
   c. **Prime Directive check:** hash each cached file against the manifest's `files` map. Clean files are overwritten with the fetched content. Modified files go through §5.1 instead — do not overwrite them here.
   d. Update `fetched_at`, `sha256`, and the `files` map in the manifest for every file actually written.
   e. **Reap files dropped from the component's set** — recorded in the manifest but absent from the fresh fetch: remove them from cache and every projection (protected ones are backed up first), and report each removal.
3. Remove any cached components that are no longer declared in **any** declaration file — but only when every declaration file loaded successfully this run; if any failed to load, skip removal entirely (§Composition, Safety). A no-longer-declared file that fails the hash check is protected: back it up to `.toolbox/backup/<date>/` before removal, and say so. Cleanup looks **only** inside `.toolbox/{skills,commands,rules,modes,agents}/`; everything else under `.toolbox/` is bookkeeping and is never swept (Prime Directive, bookkeeping invariant). While here, prune backups older than 30 days and report what was removed.
4. Refresh `supported_agents` (target set algorithm).
5. Re-sync projections for **every** name in `supported_agents`:
   a. **Prime Directive check first:** hash each existing projected file against the manifest. Clean or missing → copy from cache. Modified → §5.1; leave the file in place.
   b. Remove stale managed projected files that no longer map to declared components (same backup-if-modified rule as step 3).
   c. Never remove or overwrite unmanaged files in agent roots.
6. Write the updated `.toolbox/toolbox.json`.
7. End with a summary that names every file that was preserved, merged, staged, or backed up. Silence about a protected file is a bug.

### 5.1 Local Modifications: Detect, Merge, Preserve

When a managed file fails the hash check, someone edited it deliberately. Never revert it as a side effect of an update. You have all three versions available, so act as the merge tool:

- **base** — the previous upstream version. Its hash is in the manifest; its content is the cached copy (when the edit is in a projection). When the *cache itself* was edited, recover base from the source: for `https://` re-fetch the URL (a SHA-pinned URL reproduces it exactly); for a git-backed `file://` source, `git show <source_rev>:<repo-relative-path>` using the manifest's `source_rev` (§3). If base content cannot be recovered, fall back to a two-way comparison and be conservative.
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

**`incoming/` is derived state.** Every update pass rewrites or clears staged files to match the *current* conflict set, and resolving a conflict — merging it, adopting theirs, upstreaming, or taking ownership — deletes its staged copy. A file under `.toolbox/incoming/` therefore always means exactly one thing: an unresolved conflict from the most recent update. It is never a component and never swept as one (bookkeeping invariant, Prime Directive).

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

1. Resolve the agent name (`claude-code`, `opencode`, `grok`, `cursor`, `codex`).
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
4. At fetch time (absolute or relative form), if the resolved source lives inside a git repository, record the checkout's HEAD commit as `source_rev` in the manifest (§3). It anchors freshness reporting (§4) and merge-base recovery (§5.1) — without it, a `file://` component has no pinnable history the way a `raw.githubusercontent.com/{sha}/…` URL does.

**Note:** Absolute `file://` URLs are not portable across machines. Prefer the relative form, or `https://` for components that need to work everywhere.

## Reference Discovery

When fetching a skill's `SKILL.md`, parse it for relative markdown links to discover supporting files.

**Match patterns:**
- `[text](references/foo.md)` — standard markdown link with relative path
- `[text](some/path.md)` — any relative path (no scheme, no leading `/`)
- `` `assets/logo.svg` `` — relative paths in inline code spans, **only under the conventional `assets/` directory**. A bare shape test cannot distinguish "an asset this skill needs" from "a path this skill talks about" (`` `src/foo/core.clj` `` in copy-this-file instructions), so the `assets/` convention is the semantic boundary. These are **candidates**: fetch misses are skipped silently — unlike markdown-link references, whose fetch failures warn.

**Exclude:**
- Absolute URLs (`https://...`, `http://...`, `file://...`)
- Anchor links (`#section`)
- Paths that escape the component's own directory (`../other-skill/SKILL.md`) — those are links to **other components**, not reference files of this one. Components have no dependencies (see Limitations); a skill that needs another skill's content links to it, and the project declares both.

**`file://` skills skip discovery entirely:** the source directory **is** the component. Cache every file under it (dotfiles excluded), so assets are recorded and protected whether or not the markdown mentions them.

**Resolve:** Given a skill URL like `https://example.com/skills/solid/SKILL.md`, the base URL is `https://example.com/skills/solid/`. A reference `references/tdd.md` resolves to `https://example.com/skills/solid/references/tdd.md`.

For `file://` URLs, the same logic applies using filesystem paths.

Reference discovery applies only to skills. All other component types are single files with no references.

## Error Handling

- **Fetch failure (single component):** If a component's URL returns an error (404, timeout, network unavailable), warn the user and skip that component. Do not block the entire bootstrap or update process.
- **Fetch failure (reference file):** If a reference file fails to fetch, warn the user and continue. The skill may still be usable without it.
- **No `## Toolbox` section:** If the boot file has no `## Toolbox` section, toolbox does not apply. Do nothing.
- **Declaration file unreadable:** Warn, continue with the files that did load, and skip removal-cleanup for the whole run — never sweep components whose declaring file was unreadable (§Composition, Safety).
- **Declaration file with no recognized subsections:** Warn (probably a formatting mistake — its tools would silently not exist) and continue.
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
- **Built-in agents only.** Automatic projection targets `claude-code`, `opencode`, `grok`, `cursor`, and `codex`. Other products need a skill update (or remain unsupported).
- **Same components for every supported agent.** There is no per-agent component subset in the manifest.
- **Relative `file://` sources are only as reliable as project setup.** Toolbox does not clone them. If a declared source checkout is missing, those components are simply unavailable until the project's own setup provides it.
