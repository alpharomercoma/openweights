# The public site

Two pages, live at **<https://alpharomercoma.github.io/openweights/>**:

| Page | URL | What it is for |
|---|---|---|
| Privacy policy | <https://alpharomercoma.github.io/openweights/privacy.html> | The URL the Play listing must link to |
| Landing | <https://alpharomercoma.github.io/openweights/> | Somewhere for that link to sit, and the repository link |

## Why it is built rather than written

`privacy.html` is generated from `docs/privacy-policy.md`. Play requires the linked policy to
describe what the app actually does, and a compliance document kept in two places stops being
true the first time one copy is edited. The markdown in the repository is the only version
there is; the page is a rendering of it.

## Publishing a change

Edit `docs/privacy-policy.md`, then:

```bash
python3 play/site/build.py build/site

git worktree add --detach /tmp/ghp
git -C /tmp/ghp checkout gh-pages
cp build/site/index.html build/site/privacy.html /tmp/ghp/
git -C /tmp/ghp commit -am "Update the published policy"
git -C /tmp/ghp push origin gh-pages
git worktree remove /tmp/ghp
```

A worktree rather than switching branches, so the main tree is never touched while the site
is being built.

Change the date at the top of the markdown when the policy changes; the page reads it from
there and Play reviewers look at it.

## Why an orphan branch

Pages can serve from `docs/`, and that was the obvious option and the wrong one: `docs/` is
working notes. The roadmap, the tool-calling research, the context file. All of it is already
readable in a public repository and none of it wants a URL of its own, a nav entry, or a
Google result. The `gh-pages` branch holds three files and nothing else.

`.nojekyll` is one of them, because without it GitHub runs Jekyll over the branch, which is a
build nobody asked for and one more thing that can fail between an edit and a live policy.
