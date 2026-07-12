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
    # Create the temp file 0600 from the outset (os.open with mode) so the token
    # is never briefly world-readable between write and a later chmod.
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump({"credentials": creds}, f, indent=2)
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
# Color-pair slots (1-based); fall back to plain attributes on a mono terminal.
_TITLE, _ACTIVE, _REVOKED, _LOCK, _OPEN, _SEL, _ACCENT, _MUTE = range(1, 9)


def _tui(stdscr):
    import curses
    curses.curs_set(0)
    color = curses.has_colors()
    if color:
        curses.start_color()
        try:
            curses.use_default_colors()
            bg = -1
        except curses.error:
            bg = curses.COLOR_BLACK
        curses.init_pair(_TITLE, curses.COLOR_WHITE, curses.COLOR_BLUE)
        curses.init_pair(_ACTIVE, curses.COLOR_GREEN, bg)
        curses.init_pair(_REVOKED, curses.COLOR_RED, bg)
        curses.init_pair(_LOCK, curses.COLOR_BLACK, curses.COLOR_GREEN)
        curses.init_pair(_OPEN, curses.COLOR_BLACK, curses.COLOR_YELLOW)
        curses.init_pair(_SEL, curses.COLOR_BLACK, curses.COLOR_CYAN)
        curses.init_pair(_ACCENT, curses.COLOR_CYAN, bg)
        curses.init_pair(_MUTE, curses.COLOR_BLUE, bg)

    def cp(slot, extra=0):
        return (curses.color_pair(slot) if color else 0) | extra

    def put(y, x, s, attr=0):
        h, w = stdscr.getmaxyx()
        if 0 <= y < h and 0 <= x < w:
            # Writing the very last cell (bottom-right) throws in curses; on the
            # last row stop one short, otherwise allow the final column so the
            # right border/corners draw.
            n = (w - x - 1) if y == h - 1 else (w - x)
            if n <= 0:
                return
            try:
                stdscr.addnstr(y, x, s, n, attr)
            except curses.error:
                pass

    creds = load()
    sel, msg, kind, reveal = 0, "", "info", False

    def prompt(label):
        curses.echo()
        curses.curs_set(1)
        h, w = stdscr.getmaxyx()
        stdscr.move(h - 1, 0)
        stdscr.clrtoeol()
        put(h - 1, 1, label, cp(_ACCENT, curses.A_BOLD))
        stdscr.refresh()
        try:
            s = stdscr.getstr(h - 1, min(1 + len(label), w - 2), 48).decode("utf-8", "replace")
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
        active_n = sum(1 for c in creds if c.get("active", True))

        # title bar (full width)
        put(0, 0, (" rumahku · credentials").ljust(w), cp(_TITLE, curses.A_BOLD))
        cnt = "%d token%s " % (len(creds), "" if len(creds) == 1 else "s")
        put(0, max(0, w - len(cnt) - 1), cnt, cp(_TITLE, curses.A_BOLD))

        # lock-state banner
        if active_n:
            put(2, 2, " LOCKED · %d active token%s " % (active_n, "" if active_n == 1 else "s"),
                cp(_LOCK, curses.A_BOLD))
        else:
            put(2, 2, " OPEN · no active tokens — any client may connect ",
                cp(_OPEN, curses.A_BOLD))

        # framed list panel
        top, foot = 4, 3
        bottom = h - foot
        inner = max(0, w - 2)
        put(top, 0, "╭" + "─" * inner + "╮", cp(_MUTE))
        for yy in range(top + 1, bottom):
            put(yy, 0, "│", cp(_MUTE))
            put(yy, w - 1, "│", cp(_MUTE))
        put(bottom, 0, "╰" + "─" * inner + "╯", cp(_MUTE))
        put(top + 1, 3, "%-18s %-9s %-16s %s" % ("NAME", "STATE", "CREATED", "TOKEN"),
            cp(_ACCENT, curses.A_BOLD))

        row0 = top + 3
        if not creds:
            put(row0, 3, "no credentials yet — press  a  to add one   (backend is OPEN)",
                cp(_MUTE))
        for i, c in enumerate(creds):
            y = row0 + i
            if y >= bottom - 1:
                break
            active = c.get("active", True)
            is_sel = i == sel
            dot = "●" if active else "✕"
            state = ("%s active" if active else "%s revoked") % dot
            tok = c.get("token", "")
            shown = tok if (reveal and is_sel) else (tok[:12] + "…" if len(tok) > 12 else tok)
            name = c.get("name", "")[:18]
            created = c.get("created", "")
            if is_sel:
                put(y, 1, " " * inner, cp(_SEL))
                a = cp(_SEL, curses.A_BOLD)
                put(y, 1, "▸", a)
                put(y, 3, name.ljust(18), a)
                put(y, 22, state.ljust(9), a)
                put(y, 32, created.ljust(16), a)
                put(y, 49, shown, a)
            else:
                put(y, 3, name.ljust(18), curses.A_BOLD if active else cp(_MUTE))
                put(y, 22, state.ljust(9), cp(_ACTIVE if active else _REVOKED, curses.A_BOLD))
                put(y, 32, created.ljust(16), cp(_MUTE))
                put(y, 49, shown, cp(_MUTE))

        # message + footer key hints + path
        if msg:
            put(bottom, 2, " " + msg + " ",
                cp({"ok": _ACTIVE, "warn": _REVOKED}.get(kind, _ACCENT), curses.A_BOLD))
        x = 1
        for key, lab in (("↑↓", "move"), ("a", "add"), ("⏎", "reveal"),
                         ("r", "revoke"), ("d", "delete"), ("q", "quit")):
            put(h - 2, x, " %s " % key, cp(_TITLE, curses.A_BOLD))
            x += len(key) + 2
            put(h - 2, x, " %s   " % lab, cp(_MUTE))
            x += len(lab) + 4
        put(h - 1, 1, CREDS_FILE, cp(_MUTE))

        stdscr.refresh()
        msg, kind = "", "info"

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
            msg, kind = "Added '%s' — auth is now ON. Put this token in the app." % cred["name"], "ok"
        elif not creds:
            continue
        elif k in (curses.KEY_ENTER, 10, 13):
            reveal = not reveal
        elif k == ord("r"):
            now_active = not creds[sel].get("active", True)
            set_active(creds, sel, now_active)
            save(creds)
            msg = "'%s' is now %s." % (creds[sel].get("name", ""),
                                       "active" if now_active else "revoked")
            kind = "ok" if now_active else "warn"
        elif k == ord("d"):
            if prompt("Delete '%s'? (y/N): " % creds[sel].get("name", "")).lower() == "y":
                gone = delete(creds, sel)
                save(creds)
                reveal = False
                msg, kind = "Deleted '%s'." % gone.get("name", ""), "warn"


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
