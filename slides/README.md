# The Decision Layer — slide deck

## Quick start

First time only — see Requirements below for the one-time `npm install` setup.

```bash
export PATH="/opt/homebrew/opt/node@22/bin:$PATH"   # Node 22, see Requirements below

cd decks
npm run serve -- decision-layer.md --no-densify
```

That prints a `http://localhost:8080/...` URL — open it in a browser to view the
deck. Re-run the command and refresh the tab after editing the deck (`Ctrl-C` to
stop the server first). For a self-refreshing preview window instead of a browser
tab, use `npm run preview -- decision-layer.md`.

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
npm run serve -- decision-layer.md --no-densify   # localhost server, fast iteration
npm run html  -- decision-layer.md --no-densify   # static .html file
npm run pdf   -- decision-layer.md --no-densify   # PDF
npm run pptx  -- decision-layer.md --no-densify   # PPTX (image-based)
npm run preview -- decision-layer.md              # self-refreshing preview window
```

Output lands next to the source: `decks/decision-layer.pdf`, etc. Build artifacts
and `decks/node_modules/` are gitignored.

### Always pass `--no-densify`

Without it, `build.mjs` runs a Puppeteer pass that **rewrites the source `.md` in
place**, injecting `<!-- _class: dense -->` into any slide whose content overflows.
If a slide overflows, split it rather than shrinking it.
