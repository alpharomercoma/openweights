#!/usr/bin/env python3
"""Build the public site: a landing page, the privacy policy Play links to, and the benchmark chart.

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
WINDOW_NOTE = ROOT / "docs" / "research" / "executorch-window-matrix.md"
WINDOW_TABLES = ROOT / "docs" / "research" / "window-matrix.md"
REPO = "https://github.com/alpharomercoma/openweights"

# The app's own palette, from docs/design/visual-language.md. A policy page that looks like
# the product is a small thing, but the alternative is a Play listing whose one outbound
# link lands somewhere that could be anybody's.
STYLE = """
:root {
  --canvas: #0D0E10; --raised: #161719; --line: #26272A;
  --text: #F5F6F3; --muted: #A2A4AB; --link: #E0FF4F;
}
@media (prefers-color-scheme: light) {
  :root {
    --canvas: #FFFFFF; --raised: #F4F5F3; --line: #E7E8E4;
    --text: #052B42; --muted: #52555B;
    /* Not lime. Lime as text on white measures 1.13:1, which is why the design system
       forbids it rather than merely discouraging it. Here a link is ink and underlined. */
    --link: #052B42;
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
/* The app's mark, at the app's proportions: bars at 1, 0.7 and 0.41 of the longest, 0.22
   thick, 0.32 apart, on an ink tile. Lime on ink both ways round the theme, because a lime
   bar on a white page is the thing the palette forbids. */
.mark { display: flex; flex-direction: column; justify-content: center; gap: 6px;
        width: 96px; height: 96px; padding-left: 18px; margin-bottom: 22px;
        border-radius: 23px; background: #052B42; }
.mark i { display: block; height: 13px; border-radius: 7px; background: #E0FF4F; }
h1 { font-size: 2.05rem; line-height: 1.2; letter-spacing: -0.022em; margin: 0 0 6px; }
h2 { font-size: 1.3rem; letter-spacing: -0.012em; margin: 2.6em 0 0.7em;
     padding-top: 1.4em; border-top: 1px solid var(--line); }
h2:first-of-type { border-top: 0; padding-top: 0; }
a { color: var(--link); text-underline-offset: 3px; }
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
/* The research page: its tables run to fourteen columns, so they get the whole width
   the viewport has while the prose keeps its measure. */
.wide { max-width: 80rem; }
.wide .table-scroll { margin: 1.2em 0 1.8em; }
.wide table { font-size: 0.86rem; min-width: 0; white-space: nowrap; }
.wide th, .wide td { padding: 7px 10px; font-variant-numeric: tabular-nums; }
.wide td:first-child, .wide th:first-child { white-space: normal; }
h3 { font-size: 1.05rem; margin: 2em 0 0.5em; }
.chart { margin: 1.4em 0 0.6em; max-width: 46rem; }
.chart svg { width: 100%; height: auto; display: block; overflow: visible; }
.chart .grid { stroke: var(--line); }
.chart .axis { fill: var(--muted); font-size: 11px; }
.chart .series { fill: none; stroke: var(--text); stroke-width: 1.6; }
.chart .dot { fill: var(--text); }
.chart .name { fill: var(--text); font-size: 12px; font-weight: 600; }
.chart .kill { stroke: var(--muted); stroke-dasharray: 4 4; }
.chart .killtext { fill: var(--muted); font-size: 11px; }
.caption { color: var(--muted); font-size: 0.9rem; margin: 0 0 2em; max-width: 60ch; }
"""

MARK = '<span class="mark"><i style="width:60px"></i><i style="width:42px"></i>' \
       '<i style="width:25px"></i></span>'


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


def _render(text: str) -> str:
    html = markdown.markdown(text.strip(), extensions=["tables", "sane_lists"])
    html = html.replace("<table>", '<div class="table-scroll"><table>')
    return html.replace("</table>", "</table></div>")


def memory_chart(tables_md: str) -> str:
    """Resident memory after load against the exported window, one line per model, read
    from the probe table of window-matrix.md. Memory is the one thing the window was found
    to change, so it is the one figure the page draws rather than only tabulates."""
    series = {}
    for line in tables_md.splitlines():
        m = re.match(r"\| (\S+?)-(\d+)k \| \d+ \| .* \| (\d+) \|$", line)
        if m and "Resident" not in line:
            series.setdefault(m.group(1), []).append((int(m.group(2)), int(m.group(3))))
    if not series:
        return ""
    windows = [2, 4, 8, 16, 32]
    left, right, top, bottom, w, h = 52, 120, 16, 34, 640, 260
    plot_w, plot_h = w - left - right, h - top - bottom
    ymax = 9000
    xs = {k: left + i * plot_w / (len(windows) - 1) for i, k in enumerate(windows)}
    y = lambda mb: top + plot_h - mb / ymax * plot_h
    out = [f'<svg viewBox="0 0 {w} {h}" role="img" '
           'aria-label="Resident memory after load against exported window, per model">']
    for gb in range(0, 10, 2):
        yy = y(gb * 1000)
        out.append(f'<line class="grid" x1="{left}" x2="{left + plot_w}" y1="{yy:.1f}" y2="{yy:.1f}"/>')
        out.append(f'<text class="axis" x="{left - 8}" y="{yy + 4:.1f}" text-anchor="end">{gb} GB</text>')
    for k, x in xs.items():
        out.append(f'<text class="axis" x="{x:.1f}" y="{h - 12}" text-anchor="middle">{k}k</text>')
    kill = y(6000)
    out.append(f'<line class="kill" x1="{left}" x2="{left + plot_w}" y1="{kill:.1f}" y2="{kill:.1f}"/>')
    out.append(f'<text class="killtext" x="{left + 4}" y="{kill - 5:.1f}">Samsung Heimdall kill threshold, 6 GB</text>')
    for name, pts in series.items():
        pts.sort()
        path = " ".join(f"{'M' if i == 0 else 'L'}{xs[k]:.1f},{y(mb):.1f}" for i, (k, mb) in enumerate(pts))
        out.append(f'<path class="series" d="{path}"/>')
        for k, mb in pts:
            out.append(f'<circle class="dot" cx="{xs[k]:.1f}" cy="{y(mb):.1f}" r="3.2"/>')
        k, mb = pts[-1]
        short = name.replace("-Instruct", "").replace("-8da4w", "")
        out.append(f'<text class="name" x="{xs[k] + 8:.1f}" y="{y(mb) + 4:.1f}">{short} {mb / 1000:.1f} GB</text>')
    out.append("</svg>")
    return "\n".join(out)


def window_page() -> str:
    """The exported-window study: the research note first, then every table it reads.
    Rendered from the two markdown files so the page and the repository cannot disagree."""
    note = WINDOW_NOTE.read_text()
    title = re.match(r"^# (.*)\n", note).group(1)
    note = re.sub(r"^# .*\n", "", note, count=1)
    note = note.replace("[window-matrix.md](window-matrix.md)", "[the tables below](#numbers)")
    note = note.replace("`tools/eval/results/*8da4w-*k*.json`",
                        f"[tools/eval/results]({REPO}/tree/main/tools/eval/results)")
    note = note.replace("`tools/eval/results/invalid-nobos/`",
                        f"[tools/eval/results/invalid-nobos]({REPO}/tree/main/tools/eval/results/invalid-nobos)")
    date = re.search(r"^\*(\d{4}-\d{2}-\d{2})\.", note, re.M)

    tables = WINDOW_TABLES.read_text()
    tables = re.sub(r"^# .*\n", "", tables, count=1)
    tables = re.sub(r"^## ", "### ", tables, flags=re.M)

    chart = memory_chart(tables)
    chart_html = (
        f'<div class="chart">{chart}</div>'
        '<p class="caption">Resident memory of the test process right after each export loaded '
        "on the Dimensity 9400, from the fixed-prompt probe. The window buys nothing else that "
        "was measurable; for a full-attention model it buys a process the 12 GB phones will not "
        "keep.</p>"
    ) if chart else ""

    header = (
        f'<header>{MARK}<h1>{title}</h1>'
        f'<p class="updated">OpenWeights research &middot; {date.group(1) if date else ""}</p>'
        "</header>"
    )
    body = (
        f'<main class="wide">{_render(note)}'
        f'<h2 id="numbers">The numbers</h2>{chart_html}{_render(tables)}</main>'
    )
    footer = (
        f'<footer><a href="./">OpenWeights</a> &middot; '
        f'<a href="latency.html">Five chips, two runtimes</a> &middot; '
        f'<a href="{REPO}/blob/main/docs/research/executorch-window-matrix.md">This note in the repository</a> &middot; '
        f'<a href="{REPO}/blob/main/docs/research/window-matrix.md">The tables in the repository</a>'
        "</footer>"
    )
    return page(
        "Does the exported context window matter?",
        "ExecuTorch exports of LFM2.5 and Qwen3 at 2k to 32k context on four phone chips: "
        "answers, tool calls, speed and memory, with the same 90 prompts.",
        f"{header}{body}{footer}",
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
<li><a href="latency.html">Benchmarks: five chips, two runtimes</a></li>
<li><a href="window.html">Research: does the exported context window matter?</a></li>
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
    (out / "window.html").write_text(window_page())
    # The chart is hand-written, self-contained HTML (its data is inline); it is copied, not
    # generated, so that the file in the repository is exactly the file that is served.
    (out / "latency.html").write_text((Path(__file__).parent / "latency.html").read_text())
    # Without this GitHub runs Jekyll over the branch, which is a build nobody asked for.
    (out / ".nojekyll").write_text("")
    for name in ("index.html", "privacy.html", "latency.html", "window.html"):
        print(f"{name}: {(out / name).stat().st_size} bytes")
