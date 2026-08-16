#!/usr/bin/env python3
"""Build the public site: a landing page and the privacy policy Play links to.

The policy is generated from `docs/privacy-policy.md` rather than written twice. Play
requires the linked policy to match what the app actually does, and two copies of a
compliance document drift the first time one is edited; this way the markdown in the repo is
the only version there is and the page is a rendering of it.

    python3 play/site/build.py <output-dir>

The output is plain files with no build step of their own, published from the `gh-pages`
branch. See play/site/README.md.
"""
import re
import sys
from pathlib import Path

import markdown

ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / "docs" / "privacy-policy.md"
REPO = "https://github.com/alpharomercoma/openweights"

# The app's own palette, from docs/design/visual-language.md. A policy page that looks like
# the product is a small thing, but the alternative is a Play listing whose one outbound
# link lands somewhere that could be anybody's.
STYLE = """
:root {
  --canvas: #0B0D0F; --raised: #161A1F; --line: #262D34;
  --text: #ECF1F4; --muted: #9AA6AF; --brass: #F0A93B;
}
@media (prefers-color-scheme: light) {
  :root {
    --canvas: #FBFBF9; --raised: #F0F0EB; --line: #DCDCD6;
    --text: #14181B; --muted: #535E67; --brass: #8A5A0E;
  }
}
* { box-sizing: border-box; }
body {
  margin: 0; padding: 0 24px 96px;
  background: var(--canvas); color: var(--text);
  font: 17px/1.65 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
  -webkit-text-size-adjust: 100%;
}
main { max-width: 46rem; margin: 0 auto; }
header { max-width: 46rem; margin: 0 auto; padding: 56px 0 8px; }
.mark { display: block; margin-bottom: 22px; }
.mark i { display: block; height: 9px; border-radius: 5px; background: var(--brass);
          margin-bottom: 4px; }
h1 { font-size: 2.05rem; line-height: 1.2; letter-spacing: -0.022em; margin: 0 0 6px; }
h2 { font-size: 1.3rem; letter-spacing: -0.012em; margin: 2.6em 0 0.7em;
     padding-top: 1.4em; border-top: 1px solid var(--line); }
h2:first-of-type { border-top: 0; padding-top: 0; }
a { color: var(--brass); text-underline-offset: 3px; }
code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.9em;
       background: var(--raised); padding: 1px 5px; border-radius: 4px; }
.updated { color: var(--muted); font-size: 0.94rem; margin: 0 0 2.4em; }
.table-scroll { overflow-x: auto; margin: 1.4em 0; }
table { border-collapse: collapse; width: 100%; min-width: 34rem; font-size: 0.95rem; }
th, td { text-align: left; padding: 11px 14px; border-bottom: 1px solid var(--line);
         vertical-align: top; }
th { color: var(--muted); font-weight: 600; font-size: 0.83rem;
     letter-spacing: 0.06em; text-transform: uppercase; }
li { margin: 0.4em 0; }
footer { max-width: 46rem; margin: 4em auto 0; padding-top: 1.6em;
         border-top: 1px solid var(--line); color: var(--muted); font-size: 0.92rem; }
"""

MARK = '<span class="mark"><i style="width:64px"></i><i style="width:45px"></i>' \
       '<i style="width:26px"></i></span>'


def page(title: str, description: str, body: str) -> str:
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{description}">
<meta name="color-scheme" content="dark light">
<style>{STYLE}</style>
</head>
<body>
{body}
</body>
</html>
"""


def policy_page() -> str:
    text = POLICY.read_text()
    # The first heading and the date line become the page header, so the rendered body
    # starts at the prose. Rendering them inline would give two competing titles.
    text = re.sub(r"^# .*\n", "", text, count=1)
    updated = re.search(r"^Last updated (\d{4}-\d{2}-\d{2})\.", text, re.M)
    text = re.sub(r"^Last updated .*\n", "", text, count=1, flags=re.M)

    html = markdown.markdown(text.strip(), extensions=["tables", "sane_lists"])
    # Wide tables scroll inside their own box rather than making the page scroll sideways,
    # which is the difference between readable and not on a phone.
    html = html.replace("<table>", '<div class="table-scroll"><table>')
    html = html.replace("</table>", "</table></div>")

    header = (
        f'<header>{MARK}<h1>Privacy policy</h1>'
        f'<p class="updated">OpenWeights · last updated {updated.group(1) if updated else ""}</p>'
        "</header>"
    )
    footer = (
        f'<footer><a href="./">OpenWeights</a> · '
        f'<a href="{REPO}">Source on GitHub</a> · '
        f'<a href="{REPO}/blob/main/docs/privacy-policy.md">This policy in the repository</a>'
        "</footer>"
    )
    return page(
        "Privacy policy · OpenWeights",
        "What OpenWeights keeps on your device, and the few things that leave it.",
        f"{header}<main>{html}</main>{footer}",
    )


def landing_page() -> str:
    body = f"""<header>{MARK}<h1>OpenWeights</h1>
<p class="updated">Run open-weight language models on your Android phone.
No account, no cloud, no telemetry.</p></header>
<main>
<p>OpenWeights runs open-weight language models directly on your phone. Search Hugging Face
from inside the app, find out whether a model will actually run on your device before you
download it, and chat with it. Every token is produced by your own hardware.</p>
<h2>Links</h2>
<ul>
<li><a href="privacy.html">Privacy policy</a></li>
<li><a href="{REPO}">Source code</a></li>
<li><a href="{REPO}/issues">Report a problem</a></li>
</ul>
</main>
<footer>Apache 2.0. Models are published by third parties and their licences are their
own.</footer>"""
    return page(
        "OpenWeights",
        "Run open-weight language models on your Android phone. No account, no cloud.",
        body,
    )


if __name__ == "__main__":
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ROOT / "build" / "site")
    out.mkdir(parents=True, exist_ok=True)
    (out / "privacy.html").write_text(policy_page())
    (out / "index.html").write_text(landing_page())
    # Without this GitHub runs Jekyll over the branch, which is a build nobody asked for.
    (out / ".nojekyll").write_text("")
    for name in ("index.html", "privacy.html"):
        print(f"{name}: {(out / name).stat().st_size} bytes")
