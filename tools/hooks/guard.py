#!/usr/bin/env python3
"""Denies the handful of commands this repository must never see an agent run.

Claude Code calls this as a PreToolUse hook (`.claude/settings.json`) with the tool call as
JSON on stdin. It answers on stdout with a permission decision, or says nothing and lets the
call through. Everything it denies is a rule from AGENTS.md that a fresh session, or a
different agent, would not otherwise know:

- staging or committing the signing key, its properties file, or an env file;
- writing the Hugging Face token anywhere but the shell environment;
- rewriting published history on main, because the version code is the commit count;
- starting a Gradle build while one is already running, because the second waits on the
  first's lock and both look hung.

It is deliberately narrow. A rule that fires on ordinary git or shell use gets switched off
within a day, and then guards nothing. Run `python3 tools/hooks/guard_test.py` after editing.
"""
import json
import re
import subprocess
import sys

SECRET_FILES = re.compile(r"(^|[\s/'\"=])(upload\.jks|keystore\.properties|\.env(\.[\w.-]+)?)([\s'\"]|$)")
STAGES = re.compile(r"^\s*(?:\S+=\S+\s+)*git\s+(?:-\S+\s+)*(add|commit|stage)\b")
ADD_ALL = re.compile(r"^\s*(?:\S+=\S+\s+)*git\s+(?:-\S+\s+)*add\s+(?:-\S+\s+)*(-A|--all|\.)(\s|$)")
HF_TOKEN = re.compile(r"\bhf_[A-Za-z0-9]{30,}\b")
FORCE_PUSH = re.compile(r"^\s*(?:\S+=\S+\s+)*git\s+(?:-\S+\s+)*push\b.*(\s(--force(-with-lease)?|-f)(\s|$)|\s\+main\b)")
QUOTED = re.compile(r"'[^']*'|\"[^\"]*\"")
GRADLE = re.compile(r"^\s*(?:\S+=\S+\s+)*\.?/?gradlew(\s|$)")

# A command is judged one simple command at a time: `.git/hooks/pre-commit` in an ls, or
# `.env` in a heredoc that edits .gitignore, is not a stage of a secret. Heredoc bodies are
# not parsed; a `git add` inside one is a false positive nobody has hit.
SPLIT = re.compile(r"\n|&&|\|\||;|\|")


def segments(command: str) -> list[str]:
    return [seg for seg in SPLIT.split(command) if seg.strip()]


def gradle_running() -> bool:
    """A running Gradle client, not the daemon; the daemon idles for hours after a build.

    The wrapper script execs into a JVM, so the process to look for is not `gradlew` but
    the client's main class, GradleWrapperMain. The daemon's is GradleDaemon.
    """
    try:
        out = subprocess.run(
            ["pgrep", "-fl", "GradleWrapperMain|gradlew "], capture_output=True, text=True, timeout=5,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    return any("guard.py" not in line and "pgrep" not in line for line in out.splitlines())


def stages_secret_from_tree() -> bool:
    """`git add -A` is only a problem if the ignore rules have stopped protecting the secrets."""
    try:
        out = subprocess.run(
            ["git", "status", "--porcelain", "--untracked-files=all", "--", "upload.jks", "keystore.properties", ".env"],
            capture_output=True, text=True, timeout=5,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    return bool(out.strip())


def check_bash(command: str) -> str | None:
    if HF_TOKEN.search(command):
        return "A Hugging Face token appears literally in this command. It lives only in $HF_TOKEN; use the variable."
    for seg in segments(command):
        # A commit message may mention .env; the file names that matter are never quoted.
        bare = QUOTED.sub("", seg)
        if STAGES.search(seg) and (SECRET_FILES.search(bare) or ADD_ALL.search(seg) and stages_secret_from_tree()):
            return "This would stage upload.jks, keystore.properties or an env file. They are ignored on purpose; do not add them."
        if FORCE_PUSH.search(seg) and re.search(r"\bmain\b", seg):
            return "Force-pushing main rewrites published history, and the version code is the commit count. Denied."
        if GRADLE.search(seg) and gradle_running():
            return "A Gradle build is already running. Wait for it; two at once wait on one lock and both look hung."
    return None


def check_write(path: str) -> str | None:
    if SECRET_FILES.search(path):
        return "Editing a secrets file through the agent puts its contents in the transcript. Edit it by hand."
    return None


def main() -> None:
    try:
        call = json.load(sys.stdin)
    except json.JSONDecodeError:
        return
    tool = call.get("tool_name", "")
    args = call.get("tool_input") or {}
    reason = None
    if tool == "Bash":
        reason = check_bash(args.get("command", ""))
    elif tool in ("Write", "Edit", "MultiEdit", "NotebookEdit"):
        reason = check_write(args.get("file_path", "") or args.get("notebook_path", ""))
    if reason:
        print(json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": reason,
            },
        }))


if __name__ == "__main__":
    main()
