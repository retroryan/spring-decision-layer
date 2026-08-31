# The Decision Layer — slide deck

## Quick start

First time only — see Requirements below for the one-time `npm install` setup.

```bash
export PATH="/opt/homebrew/opt/node@22/bin:$PATH"   # Node 22, see Requirements below

cd decks
npm run html -- decision-layer.md --no-densify
open decision-layer.html
```

That builds the deck to `decks/decision-layer.html` and opens it in your default
browser. Re-run the `npm run html` line and refresh the tab after editing the deck.
For live-reload while editing, use `npm run preview -- decision-layer.md` instead
(opens a self-refreshing preview window).

Marp deck built on the [Neo4j Marp template](https://github.com/halftermeyer/neo4j-marp-template),
vendored into `decks/` so the deck builds from a fresh clone.

- `decks/decision-layer.md` — the deck
- `decks/package.json`, `decks/build.mjs`, `decks/marp.config.mjs`, `decks/neo4j.css` — the template
- `slides-outline-v3.md` — the talk outline the deck was written from
- `images/` — the deck's diagrams (hand-authored SVG)
- `assets/` — Neo4j brand art from the template

## Requirements

**Node 22.** Marp CLI 4.2.3 fails on Node 26 (its `yargs` dependency loads an
extensionless CJS file that Node 26 resolves as ESM). On this machine:

```bash
export PATH="/opt/homebrew/opt/node@22/bin:$PATH"
```

**One-time setup** — install dependencies (re-run only after `decks/package.json`
or `package-lock.json` change):

```bash
cd decks
npm install
```

## Build

```bash
npm run html -- decision-layer.md --no-densify   # fast iteration
npm run pdf  -- decision-layer.md --no-densify    # PDF
npm run pptx -- decision-layer.md --no-densify    # PPTX (image-based)
npm run preview -- decision-layer.md              # live reload
```

Output lands next to the source: `decks/decision-layer.pdf`, etc. Build artifacts
and `decks/node_modules/` are gitignored.

### Always pass `--no-densify`

Without it, `build.mjs` runs a Puppeteer pass that **rewrites the source `.md` in
place**, injecting `<!-- _class: dense -->` into any slide whose content overflows.
If a slide overflows, split it rather than shrinking it.
