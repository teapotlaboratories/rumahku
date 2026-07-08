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

**Agentic AI must NOT run `git commit` or `git push` during work hours at all.**
Backdating the commit date does not hide the push: the GitHub push/PR event
timestamp is the real wall-clock time and cannot be amended. So if you are
inside work hours, prepare the change (edit files, stage) but **wait** until
after 18:00 on a weekday, or any time on a weekend, to actually commit and push.
Outside work hours, commit and push normally.

For commit *dates* (the history timestamps): the author date *and* committer
date must also fall **outside** work hours — which they will automatically if
you only ever commit outside work hours per the rule above.

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

## 6. Keep a daily worklog under `docs/worklog/`

Maintain a running worklog, **one file per day**, named
`docs/worklog/YYYY-MM-DD.md` (local date). At the start of a work session,
open (or create) that day's file and append to it as you go. Each entry should
capture, briefly: what was done, key decisions/findings, commands or artifacts
that matter, current state, and the next step to pick up. The goal is that any
agent (or human) can resume cold by reading the latest worklog. Worklogs are
docs, so Rule 3 applies (may go straight to `main`).

## 7. Keep the bench setup documented under `docs/`

Maintain `docs/BENCH.md` describing the hardware/lab bench used for the project
— the machines, devices, GPUs, how they connect (mesh, SSH, adb), keys/hosts,
and how to reach each one. Update it whenever the bench changes (a new device,
host, key, or connection path). It is the single source of truth for "how do I
connect to X"; do not rely on chat history for that.

## 8. Write a design/plan doc before building

Before implementing a milestone, phase, or any non-trivial feature, capture the
plan first as a Markdown design doc under `docs/` (e.g. `docs/PHASE2.md`,
`docs/M1.md`). It should cover: the goal, the approach and key decisions, the
interface/API surface being added, risks (ranked), and a task sequence. Keep it
updated as the design changes, and record the outcome (what shipped, what the
spike proved) when done. Design docs are docs, so Rule 3 applies (may go
straight to `main`); the implementation that follows obeys Rule 4 (branch + PR).

## 9. Keep `docs/MILESTONES.md` current as the roadmap + to-do list

`docs/MILESTONES.md` is the single source of truth for what's done, in progress,
and planned — a checkbox to-do list grouped by milestone. Update it whenever work
starts, lands, or is (re)planned: check off finished items, flip statuses
(✅/🔨/⬜/⏸), and add new tasks as they surface. Check it at the start of a work
session to pick up the thread. It's docs (Rule 3).
