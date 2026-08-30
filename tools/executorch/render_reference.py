#!/usr/bin/env python3
"""Render Qwen3's real chat template, so the Kotlin transcription can be proved.

llama.cpp reads a model's chat template out of the GGUF and renders it with a Jinja
engine. ExecuTorch does neither: a `.pte` is a compiled graph and a tokenizer, and the
prompt handed to it is whatever the app builds. So `Qwen3Prompt.kt` is a transcription of
a template maintained by somebody else, and a transcription that drifts does not fail — the
model answers slightly worse and tool calls quietly stop parsing.

This renders the template the model actually ships with, over the same conversations the
Kotlin tests use, and writes the results out as a Kotlin fixture file. Regenerate it when
the upstream template changes and read the diff:

    tools/executorch/render_reference.py --out core/common/src/jvmAndAndroidTest/kotlin/\
io/github/alpharomercoma/openweights/core/common/model/Qwen3PromptFixtures.kt

Requires jinja2. The template is fetched from the Hub rather than vendored, because the
point is to compare against upstream rather than against a copy of it.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

MODEL = "Qwen/Qwen3-1.7B"
TOKENIZER_CONFIG = f"https://huggingface.co/{MODEL}/raw/main/tokenizer_config.json"

# One tool, spelled the way ToolDefinition holds it: a name, a description and a JSON
# Schema object. The schema is spliced into the prompt verbatim on the Kotlin side, so it
# is written here in the exact form `json.dumps` produces — any other spacing would make
# the fixtures disagree for a reason that has nothing to do with the template.
SEARCH_TOOL = {
    "type": "function",
    "function": {
        "name": "web_search",
        "description": "Search the web for current information.",
        "parameters": {
            "type": "object",
            "properties": {"query": {"type": "string", "description": "What to search for"}},
            "required": ["query"],
        },
    },
}

CASES: dict[str, dict] = {
    # No system message and no tools: Qwen3 has no default system prompt, so the prompt
    # opens straight into the user turn. An empty system block here is a real difference.
    "plain": {
        "messages": [{"role": "user", "content": "What is the capital of Japan?"}],
    },
    "withSystem": {
        "messages": [
            {"role": "system", "content": "You are a terse assistant."},
            {"role": "user", "content": "What is the capital of Japan?"},
        ],
    },
    # Thinking off is spelled as a closed empty block in the assistant opener, not a flag.
    "thinkingDisabled": {
        "messages": [{"role": "user", "content": "What is the capital of Japan?"}],
        "enable_thinking": False,
    },
    "withTools": {
        "messages": [{"role": "user", "content": "What is the weather in Manila?"}],
        "tools": [SEARCH_TOOL],
    },
    "withSystemAndTools": {
        "messages": [
            {"role": "system", "content": "You are a terse assistant."},
            {"role": "user", "content": "What is the weather in Manila?"},
        ],
        "tools": [SEARCH_TOOL],
    },
    # A system message that is not first is an ordinary turn, rendered in place. This is
    # the case the hand-written version got wrong by hoisting it.
    "systemNotFirst": {
        "messages": [
            {"role": "user", "content": "Hello."},
            {"role": "system", "content": "Be brief from now on."},
            {"role": "user", "content": "What is the capital of Japan?"},
        ],
    },
    # Thinking survives in the turns after the user's last real question and is dropped
    # from everything before it. Two user turns are what makes the boundary visible.
    "priorThinkingDropped": {
        "messages": [
            {"role": "user", "content": "What is 2+2?"},
            {"role": "assistant", "content": "<think>\nSimple arithmetic.\n</think>\n\nFour."},
            {"role": "user", "content": "And 3+3?"},
        ],
    },
    # The agentic shape: a tool call, its result delivered as a user turn, and the model
    # still mid-run. The result is wrapped in <tool_response>, so it is not the last query.
    "toolRun": {
        "messages": [
            {"role": "user", "content": "What is the weather in Manila?"},
            {
                "role": "assistant",
                "content": "<think>\nI should look this up.\n</think>\n\n",
                "tool_calls": [
                    {
                        "type": "function",
                        "function": {"name": "web_search", "arguments": {"query": "Manila weather"}},
                    }
                ],
            },
            {"role": "tool", "content": "Manila: 31C, humid."},
        ],
        "tools": [SEARCH_TOOL],
    },
    # Two results in a row collapse into one user turn holding both responses.
    "twoToolResults": {
        "messages": [
            {"role": "user", "content": "Compare Manila and Tokyo."},
            {"role": "assistant", "content": "Looking both up."},
            {"role": "tool", "content": "Manila: 31C."},
            {"role": "tool", "content": "Tokyo: 22C."},
        ],
        "tools": [SEARCH_TOOL],
    },
}


def fetch_template() -> str:
    """The template as the model ships it. curl, because this Mac's Python has no CA certs."""
    raw = subprocess.run(
        ["curl", "-sSf", "--max-time", "30", TOKENIZER_CONFIG],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    template = json.loads(raw).get("chat_template")
    if not isinstance(template, str):
        raise SystemExit(f"{MODEL} has no string chat_template")
    return template


def render(template: str, case: dict) -> str:
    from jinja2 import Environment
    from jinja2.exceptions import TemplateError

    def raise_exception(message: str):
        raise TemplateError(message)

    environment = Environment(trim_blocks=True, lstrip_blocks=True)
    environment.filters["tojson"] = lambda value, **kwargs: json.dumps(value, **kwargs)
    environment.globals["raise_exception"] = raise_exception

    return environment.from_string(template).render(
        messages=case["messages"],
        tools=case.get("tools"),
        add_generation_prompt=case.get("add_generation_prompt", True),
        enable_thinking=case.get("enable_thinking", True),
    )


# Source lines cap at 100 characters (.editorconfig), and a rendered prompt is one long
# string. Breaking it at its own newlines rather than at an arbitrary column means each
# source line holds one line of the prompt, so a diff after an upstream template change
# reads as the prompt changing rather than as the wrapping moving.
MAX_CONTENT = 80

ESCAPES = {
    chr(92): chr(92) * 2,
    chr(34): chr(92) + chr(34),
    chr(10): chr(92) + "n",
    chr(36): chr(92) + chr(36),
}


def chunk(text: str) -> str:
    """The string as indented, concatenated Kotlin literals, split on its own newlines."""
    pieces: list[str] = []
    current = ""
    for character in text:
        escaped = ESCAPES.get(character, character)
        if len(current) + len(escaped) > MAX_CONTENT and current:
            pieces.append(current)
            current = ""
        current += escaped
        if character == chr(10):
            pieces.append(current)
            current = ""
    if current:
        pieces.append(current)
    if not pieces:
        pieces = [""]

    lines = ['        "' + pieces[0] + '"']
    for piece in pieces[1:]:
        lines.append('            "' + piece + '"')
    return " +\n".join(lines)


def as_kotlin(rendered: dict[str, str]) -> str:
    """The fixtures as a Kotlin file, so the expected prompts are visible in review."""
    header = """/*
 * Copyright 2026 The OpenWeights Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.alpharomercoma.openweights.core.common.model

/**
 * Prompts rendered by the `chat_template` that MODEL_NAME ships with.
 *
 * Generated, not written. `tools/executorch/render_reference.py` fetches the template from
 * the Hub and renders it with Jinja over the same conversations `Qwen3PromptTest` builds,
 * so these strings are what upstream produces rather than anybody's idea of what it should
 * produce. Do not edit by hand: rerun the script and read the diff.
 */
internal object Qwen3PromptFixtures {
"""
    header = header.replace("MODEL_NAME", MODEL)

    body = []
    for name, text in rendered.items():
        constant = re.sub("([a-z0-9])([A-Z])", r"\1_\2", name).upper()
        body.append("    const val " + constant + ": String =\n" + chunk(text) + "\n")
    return header + "\n".join(body) + "}\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="Kotlin fixture file to write")
    parser.add_argument("--print", action="store_true", help="show each rendered prompt")
    args = parser.parse_args()

    template = fetch_template()
    rendered = {name: render(template, case) for name, case in CASES.items()}

    if args.print or not args.out:
        for name, text in rendered.items():
            print(f"===== {name} =====")
            print(text)
            print()

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(as_kotlin(rendered))
        print(f"wrote {len(rendered)} fixtures to {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
