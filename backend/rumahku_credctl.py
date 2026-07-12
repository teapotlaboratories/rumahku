#!/usr/bin/env python3
"""rumahku credential manager — a small curses TUI to manage the backend's API
tokens.

The backend (serve.py) authorizes a request when its `Authorization: Bearer
<token>` matches an *active* credential in CREDS_FILE (or the RUMAHKU_TOKEN env
token). This tool is how you mint, reveal, revoke and delete those credentials —
one per device/client, so any single one can be revoked without disturbing the
rest. serve.py re-reads the file on its next request (mtime-cached), so changes
take effect live, no restart needed.

IMPORTANT: adding the *first* credential switches the backend from OPEN to
REQUIRING auth — every client must then present a valid token (the app carries it
in Settings -> "Access token"). Deleting/revoking the last one reopens it.

Run over SSH on the backend:  python3 rumahku_credctl.py
Non-interactive helpers (for scripts / no TTY):
  python3 rumahku_credctl.py --list
  python3 rumahku_credctl.py --add "<name>"      # prints the new token
Stdlib only (curses, json, secrets) — matches serve.py's zero-dependency design.
"""
import json
import os
import secrets
import sys
from datetime import datetime

CREDS_FILE = os.environ.get(
    "RUMAHKU_CREDS", os.path.expanduser("~/rumahku/credentials.json"))


# ── store (pure functions, unit-testable without curses) ──────────────────────
def load():
    """Return the credentials list (possibly empty). Tolerant of a missing or
    malformed file — a broken file reads as 'no credentials', never an error."""
    try:
        with open(CREDS_FILE) as f:
            data = json.load(f)
    except (OSError, ValueError):
        return []
    creds = data.get("credentials") if isinstance(data, dict) else None
    return creds if isinstance(creds, list) else []


def save(creds):
    """Write credentials atomically (temp + rename) with 0600 perms — this file
    holds secrets, so keep it owner-only and never leave a half-written file."""
    os.makedirs(os.path.dirname(CREDS_FILE) or ".", exist_ok=True)
    tmp = CREDS_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump({"credentials": creds}, f, indent=2)
    try:
        os.chmod(tmp, 0o600)
    except OSError:
        pass
    os.replace(tmp, CREDS_FILE)


def gen_token():
    """A URL-safe random bearer token, prefixed so it's recognisable in logs."""
    return "rmk_" + secrets.token_urlsafe(24)


def add(creds, name):
    """Append a new active credential with a freshly generated token."""
    cred = {"name": (name or "device").strip()[:32] or "device",
            "token": gen_token(),
            "created": datetime.now().strftime("%Y-%m-%d %H:%M"),
            "active": True}
    creds.append(cred)
    return cred


def set_active(creds, i, active):
    creds[i]["active"] = active


def delete(creds, i):
    return creds.pop(i)


# ── non-interactive fallbacks (no TTY / scripting) ────────────────────────────
def _cli_list():
    creds = load()
    if not creds:
        print("(no credentials — backend is OPEN)")
        return
    for c in creds:
        print("%-20s %-8s %-16s %s" % (
            c.get("name", ""), "active" if c.get("active", True) else "revoked",
            c.get("created", ""), c.get("token", "")))


def _cli_add(name):
    creds = load()
    cred = add(creds, name)
    save(creds)
    print(cred["token"])   # just the token, so it's easy to pipe/copy


# ── curses TUI ────────────────────────────────────────────────────────────────
def _tui(stdscr):
    import curses
    curses.curs_set(0)
    creds = load()
    sel, msg, reveal = 0, "", False

    def prompt(label):
        curses.echo()
        curses.curs_set(1)
        h, w = stdscr.getmaxyx()
        stdscr.move(h - 2, 0)
        stdscr.clrtoeol()
        stdscr.addnstr(h - 2, 0, label, w - 1, curses.A_BOLD)
        stdscr.refresh()
        try:
            s = stdscr.getstr(h - 2, min(len(label), w - 2), 48).decode("utf-8", "replace")
        except Exception:  # noqa: BLE001
            s = ""
        curses.noecho()
        curses.curs_set(0)
        return s.strip()

    while True:
        if creds:
            sel = max(0, min(sel, len(creds) - 1))
        stdscr.erase()
        h, w = stdscr.getmaxyx()
        stdscr.addnstr(0, 0, "rumahku credentials — backend API tokens", w - 1, curses.A_BOLD)
        stdscr.addnstr(1, 0, CREDS_FILE, w - 1, curses.A_DIM)
        active_n = sum(1 for c in creds if c.get("active", True))
        state = ("AUTH REQUIRED — %d active token%s" % (active_n, "" if active_n == 1 else "s")
                 if active_n else "OPEN — no active tokens (any client may connect)")
        stdscr.addnstr(2, 0, state, w - 1,
                       curses.A_BOLD | (curses.A_REVERSE if not active_n else 0))
        stdscr.addnstr(4, 0, "  %-18s %-8s %-16s %s" % ("NAME", "STATE", "CREATED", "TOKEN"),
                       w - 1, curses.A_UNDERLINE)
        if not creds:
            stdscr.addnstr(6, 2, "(no credentials yet — press 'a' to add one)", w - 3)
        for i, c in enumerate(creds):
            if 5 + i >= h - 2:
                break
            tok = c.get("token", "")
            shown = tok if (reveal and i == sel) else (tok[:10] + "…" if len(tok) > 10 else tok)
            row = "  %-18s %-8s %-16s %s" % (
                c.get("name", "")[:18], "active" if c.get("active", True) else "revoked",
                c.get("created", ""), shown)
            attr = curses.A_REVERSE if i == sel else 0
            if not c.get("active", True):
                attr |= curses.A_DIM
            stdscr.addnstr(5 + i, 0, row, w - 1, attr)
        stdscr.addnstr(h - 2, 0, msg[:w - 1], w - 1, curses.A_BOLD)
        stdscr.addnstr(h - 1, 0,
                       "↑↓ move  a add  ⏎ reveal  r revoke/enable  d delete  q quit",
                       w - 1, curses.A_DIM)
        stdscr.refresh()
        msg = ""

        k = stdscr.getch()
        if k in (ord("q"), 27):
            break
        elif k in (curses.KEY_UP, ord("k")):
            sel -= 1
            reveal = False
        elif k in (curses.KEY_DOWN, ord("j")):
            sel += 1
            reveal = False
        elif k == ord("a"):
            cred = add(creds, prompt("New credential name: "))
            save(creds)
            sel, reveal = len(creds) - 1, True
            msg = "Added '%s' — auth is now ON. Put this token in the app's Settings." % cred["name"]
        elif not creds:
            continue
        elif k in (curses.KEY_ENTER, 10, 13):
            reveal = not reveal
        elif k == ord("r"):
            set_active(creds, sel, not creds[sel].get("active", True))
            save(creds)
            msg = "'%s' is now %s." % (
                creds[sel].get("name", ""),
                "active" if creds[sel].get("active", True) else "revoked")
        elif k == ord("d"):
            if prompt("Delete '%s'? (y/N): " % creds[sel].get("name", "")).lower() == "y":
                gone = delete(creds, sel)
                save(creds)
                reveal = False
                msg = "Deleted '%s'." % gone.get("name", "")


def main(argv):
    if "--list" in argv:
        _cli_list()
        return 0
    if "--add" in argv:
        i = argv.index("--add")
        _cli_add(argv[i + 1] if i + 1 < len(argv) else "device")
        return 0
    try:
        import curses
    except ImportError:
        print("curses unavailable; use --list / --add", file=sys.stderr)
        return 1
    if not sys.stdout.isatty():
        print("not a TTY — run interactively over SSH, or use --list / --add",
              file=sys.stderr)
        return 1
    curses.wrapper(_tui)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
