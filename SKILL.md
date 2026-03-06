---
name: toolbox
description: Manages skill dependencies for a project. Parses skill URLs from the project's boot file, fetches them into a local .skills/ cache, tracks freshness, and updates on demand. Use this skill when a project's boot file declares skills via URL.
---

# Toolbox — Skill Management

Resolve, cache, and update skill dependencies declared in a project's boot file.

## When This Skill Applies

When you land in a project and the boot file contains a `## Skills` section with a link to this skill (toolbox), follow the procedure below to ensure all declared skills are available locally before doing any work.

## How Skills Are Declared

Skills are declared in the project's boot file under a `## Skills` section. The boot file is whatever file the agent reads on startup — `AGENTS.md`, `CLAUDE.md`, or any platform-specific equivalent. Each skill is a markdown link in a bullet list:

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

- The **link text** is the skill name.
- The **URL** points to the skill's `SKILL.md` entry point.
- Both `https://` and `file://` URLs are supported.
- Skill names must be unique. If duplicates are found, warn the user and use the last declaration.

## Procedure

### 1. Check for Cached Skills

Look for `.skills/toolbox.json` in the project root.

- **If it exists**: skills have been fetched before. Check for updates (see §4).
- **If it doesn't exist**: bootstrap (see §2).

### 2. Bootstrap (First Run)

When `.skills/toolbox.json` is missing:

1. Create the `.skills/` directory in the project root.
2. Parse the `## Skills` section of the boot file for skill links. Extract each `[name](url)` pair.
3. For each declared skill (including toolbox itself — use the already-fetched copy rather than re-fetching):
   a. Fetch `SKILL.md` from the skill's URL.
   b. Discover reference files by parsing relative markdown links in `SKILL.md` — patterns like `[text](references/foo.md)` or `[text](some/path.md)`. Only include links to relative paths (not absolute URLs or anchors).
   c. Compute the base URL by removing `SKILL.md` from the skill's URL. Fetch each discovered reference file relative to that base URL.
   d. Write all fetched files into `.skills/{name}/`, preserving directory structure.
   e. Compute a SHA-256 hash covering all fetched files (concatenate file contents in sorted order by path, then hash).
4. Write `.skills/toolbox.json` with the manifest (see §3).
5. Ensure `.skills/` is listed in the project's `.gitignore`. If not, add it.

### 3. The Manifest — `.skills/toolbox.json`

The manifest tracks all cached skills, their source URLs, fetched files, and content hashes for change detection. Example (hashes and timestamps are illustrative):

```json
{
  "skills": {
    "toolbox": {
      "url": "https://raw.githubusercontent.com/slagyr/toolbox/main/SKILL.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "a1b2c3d4e5f6...",
      "files": ["SKILL.md"]
    },
    "tdd": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-skills/main/tdd/SKILL.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "f6e5d4c3b2a1...",
      "files": ["SKILL.md"]
    },
    "braids": {
      "url": "https://raw.githubusercontent.com/slagyr/braids/main/braids/SKILL.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "1a2b3c4d5e6f...",
      "files": [
        "SKILL.md",
        "references/worker.md",
        "references/orchestrator.md",
        "references/project-creation.md",
        "references/init.md",
        "references/migration.md",
        "references/agents-template.md",
        "references/coordinator-constraint-snippet.md",
        "references/worker-agent-template.md"
      ]
    }
  }
}
```

**Fields:**

| Field | Description |
|-------|-------------|
| `skills` | Map of skill name → skill entry. |
| `skills.{name}.url` | The URL from which `SKILL.md` was fetched. |
| `skills.{name}.fetched_at` | ISO 8601 timestamp of when the skill was last fetched. |
| `skills.{name}.sha256` | SHA-256 hash covering all of the skill's files at fetch time. Computed by concatenating file contents in sorted order by path, then hashing. Used to detect remote changes. |
| `skills.{name}.files` | List of all files cached for this skill, relative to `.skills/{name}/`. |

### 4. Check for Updates

Toolbox detects updates by comparing content, not by time. Each skill's `sha256` in the manifest is the hash of all its files at fetch time.

**On session start**, if the cached skills exist, proceed silently. Do not fetch anything automatically — the cached versions are ready to use.

**When the user asks** (e.g., "check for skill updates", "are my skills up to date?"):

1. For each skill in the manifest, fetch `SKILL.md` and all reference files from the URL.
2. Compute the SHA-256 hash covering all fetched files (same method as bootstrap).
3. Compare to the stored `sha256` in the manifest.
4. Report results:
   ```
   Skill updates available:
     - braids (changed)
     - tdd (up to date)
   Update skills? [y/n]
   ```
5. If the user confirms, proceed with §5 (Update Skills) for the changed skills.

### 5. Update Skills

When the user asks to update skills (e.g., "update skills", "refresh skills"):

1. Re-parse the boot file for the current skill declarations. This catches added or removed skills.
2. For each declared skill:
   a. Re-fetch `SKILL.md` from the URL.
   b. Re-discover and fetch reference files.
   c. Overwrite the cached files in `.skills/{name}/`.
   d. Update `fetched_at` and `sha256` in the manifest.
3. Remove any cached skills that are no longer declared in the boot file.
4. Write the updated `.skills/toolbox.json`.

### 6. Read Skills

When you need to read a skill's content during a session, read from `.skills/{name}/SKILL.md`. References are at `.skills/{name}/references/` (or wherever the skill's relative links point).

## URL Schemes

### `https://`

Fetch via HTTP GET. This is the primary use case for portable, published skills.

For skills hosted on GitHub, use `raw.githubusercontent.com` URLs:
```
https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{name}/SKILL.md
```

To pin a specific version, use a commit SHA instead of a branch name:
```
https://raw.githubusercontent.com/{owner}/{repo}/{sha}/{name}/SKILL.md
```

**Private repos:** `raw.githubusercontent.com` does not serve files from private repositories without authentication. For private skills, use `file://` URLs or a URL that includes an access token.

### `file://`

Copy from the local filesystem. Useful for:
- Skills under active development
- Private skills that won't be published
- Migration from filesystem-based skill references

Example:
```markdown
- [braids](file:///Users/micah/Projects/braids/braids/SKILL.md)
```

**Note:** `file://` URLs are not portable across machines. Use `https://` for skills that need to work everywhere.

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

## Error Handling

- **Fetch failure (single skill):** If a skill's URL returns an error (404, timeout, network unavailable), warn the user and skip that skill. Do not block the entire bootstrap or update process.
- **Fetch failure (reference file):** If a reference file fails to fetch, warn the user and continue. The skill may still be usable without it.
- **No `## Skills` section:** If the boot file has no `## Skills` section, toolbox does not apply. Do nothing.
- **Invalid `file://` path:** If a `file://` path does not exist, treat it as a fetch failure — warn and skip.
- **General rule:** Never silently swallow errors. Always inform the user what failed and why.

## Limitations

- **No skill dependencies.** Toolbox treats each skill as independent. If skill A requires skill B, the skill author should note this in their `SKILL.md` description so that projects declare both skills explicitly.
