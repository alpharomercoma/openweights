#!/usr/bin/env python3
"""Runs the guard against the calls it must deny and the ordinary ones it must not.

    python3 tools/hooks/guard_test.py

Each case is fed to guard.py the way Claude Code feeds it, as JSON on stdin, so the test
covers the wiring and not only the regexes. The Gradle concurrency case is not here: it
depends on a live process and is checked by hand (start a build, ask for another).
"""
import json
import subprocess
import sys
from pathlib import Path

GUARD = Path(__file__).with_name("guard.py")

DENY = [
    ("Bash", {"command": "git add upload.jks"}),
    ("Bash", {"command": "git add keystore.properties && git commit -m x"}),
    ("Bash", {"command": "git add .env.local"}),
    ("Bash", {"command": "git commit -am 'x' -- keystore.properties"}),
    ("Bash", {"command": "echo hf_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij > token.txt"}),
    ("Bash", {"command": "export HF_TOKEN=hf_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij"}),
    ("Bash", {"command": "git push --force origin main"}),
    ("Bash", {"command": "git push -f https://github.com/alpharomercoma/openweights.git main"}),
    ("Bash", {"command": "git push origin +main"}),
    ("Bash", {"command": "cd /tmp\ngit add .env\ngit status"}),
    ("Bash", {"command": "HF=x git add -f upload.jks"}),
    ("Bash", {"command": "git push --force-with-lease origin main"}),
    ("Write", {"file_path": "/Users/alpha/mobile-inference/keystore.properties", "content": "x"}),
    ("Edit", {"file_path": "/Users/alpha/mobile-inference/.env", "old_string": "a", "new_string": "b"}),
]

ALLOW = [
    ("Bash", {"command": "git add app/src/main/kotlin/Foo.kt && git commit -m 'chat: x'"}),
    ("Bash", {"command": "git add -A && git status"}),
    ("Bash", {"command": "git push https://github.com/alpharomercoma/openweights.git main"}),
    ("Bash", {"command": "git push --force-with-lease origin feature/x"}),
    ("Bash", {"command": "curl -H \"Authorization: Bearer $HF_TOKEN\" https://huggingface.co/api/whoami"}),
    ("Bash", {"command": "ls -la upload.jks keystore.properties"}),
    ("Bash", {"command": "grep -n storeFile app/build.gradle.kts"}),
    ("Bash", {"command": "cat .envrc.example"}),
    ("Bash", {"command": "lefthook install && ls -la .git/hooks/pre-commit && git status --short"}),
    ("Bash", {"command": "cat >> .gitignore <<'EOF'\n# must not be able to stage it.\n.env\n.env.*\nEOF\ngit status"}),
    ("Bash", {"command": "git commit -m 'hooks: ignore .env files' -- .gitignore"}),
    ("Write", {"file_path": "/Users/alpha/mobile-inference/docs/environment.md", "content": "x"}),
    ("Edit", {"file_path": "/Users/alpha/mobile-inference/app/src/main/kotlin/Env.kt", "old_string": "a", "new_string": "b"}),
]


def decision(tool: str, args: dict) -> str | None:
    payload = json.dumps({"tool_name": tool, "tool_input": args})
    out = subprocess.run([sys.executable, str(GUARD)], input=payload, capture_output=True, text=True).stdout
    if not out.strip():
        return None
    return json.loads(out)["hookSpecificOutput"]["permissionDecision"]


def main() -> int:
    failures = []
    for tool, args in DENY:
        if decision(tool, args) != "deny":
            failures.append(f"should deny: {tool} {args}")
    for tool, args in ALLOW:
        if decision(tool, args) is not None:
            failures.append(f"should allow: {tool} {args}")
    for f in failures:
        print(f)
    print(f"{len(DENY) + len(ALLOW) - len(failures)} of {len(DENY) + len(ALLOW)} cases as expected")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
