# AI Agent Rules

Binding rules for any agentic AI working in this repository. Follow them
exactly.

## 1. No AI attribution anywhere

Do not add any agentic-AI attributes, markers, or authorship signals to
**anything** — source files, comments, headers, documentation, commit
messages, PR titles, or PR descriptions. This includes (non-exhaustively):

- `Co-Authored-By:` trailers naming an AI
- "Generated with …", "Claude-Session", or similar footers/links
- Comments or docstrings crediting an AI author
- Any "🤖" / assistant branding

Commits and PRs must read as ordinary human-authored work.

## 2. No work-hours timestamps in pushed history

Work hours are **Monday–Friday, 09:00–18:00 (local time)**.

You may create commits at any time. But **before pushing**, the commit's
author date *and* committer date must fall **outside** work hours — i.e.
after 18:00 on a weekday, or any time on a weekend.

If you commit during work hours, amend the dates before pushing:

```sh
D="2026-07-06T19:30:00"   # any time after 18:00 that day (or a weekend)
GIT_COMMITTER_DATE="$D" git commit --amend --no-edit --date="$D"
git push
```

Both `GIT_AUTHOR_DATE` (via `--date`) and `GIT_COMMITTER_DATE` (via env)
must be set, or one will still show a work-hours time. If you are already
outside work hours, no amend is needed.

## 3. Docs-only changes may go straight to `main`

If a change touches **only** documentation (e.g. `*.md`, files under
docs, comments-only edits with no behavior change), it may be committed
and pushed directly to `main` (subject to Rule 2's timing).

## 4. Everything else goes on a branch + PR

Any change that is not docs-only must be made on a feature branch and
submitted as a Pull Request. Never push non-docs changes directly to
`main`.

## 5. PR merges are always "Rebase and merge"

When merging a PR, always use **Rebase and merge**. Never use a merge
commit or squash merge.
