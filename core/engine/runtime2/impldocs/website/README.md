# Viaduct Runtime2 Tour

A top-down, animated tour of the design of Viaduct's Runtime2 engine ([`core/engine/runtime2`](../..)), from the big picture down to the code.

## Open it

Static HTML with no build step. Source links are relative paths into this repository, so serve the repository root over http, not just this folder:

```bash
python3 -m http.server 8000
```

Run that from the repository root, then open `http://localhost:8000/core/engine/runtime2/impldocs/website/`.

Opening `index.html` straight from disk mostly works, but the glossary popups on linked terms need http: they `fetch` `glossary.html`, and browsers block `fetch` on `file://`.

Each scene plays one step at a time: Prev and Next (or the arrow keys) move between steps, a step dot jumps to that step, the timeline scrubs, and Autoplay plays the rest. On the concept map, click a node or take the guided tour. The theme follows your OS light/dark setting. The ◐ button overrides it, and so does `?theme=light|dark`.

## Chapters

Overview: Viaduct's Runtime2 engine (`index.html`), then:

1. The actors: orchestrators, resolver tasks, the result tree, the registry
2. Demand and supply: which fields come with the parent's output and which need their own resolver
3. A request, end to end
4. Exercises

## Deep dives

Advanced topics live in `deep-dives/`, listed on `deep-dives/index.html`; each page can be read on its own:

- Motivation (start here, 4 parts): why Viaduct needs an engine of its own on top of GraphQL Java, and why it plans before it runs
- Nested inputs and node references: a list element that needs an unasked-for field, and node references
- Arguments and aliases
- Selectivity
- Access checks
- @skip and @include
- Field selection forests
- Demand closure
- Into the code: package map, concept-to-code table and excerpts
- Mutations
- Cycles
- Type hierarchies: how the concepts connect, and the type families behind them
- Reference resolver families

The tour uses one running example, a Viaduct-style `Venue` with a `location: Location`, from the overview to chapter 3, and the exercises in chapter 4 reuse it. Several deep dives extend it.

## How it works

The site is static: hand-written HTML, CSS and JS, with no build step and nothing generated. Content lives in the HTML as markup and data attributes. The scripts in `assets/` are generic renderers that read it:

| Content | Where it lives | Rendered by |
|---|---|---|
| Animated scenes | `figure.scene`: an inline SVG whose elements carry `data-in`, `data-out`, `data-text` and similar times, plus `ol.beats > li[data-at]` captions | `assets/motion.js` (markup reference in its header) |
| Concept graph | `figure.cgraph` with `div.cg-node`, `div.cg-edge` and an `ol.cg-tour` | `assets/graph.js` (header) |
| Exercises | `.drill` blocks inside a `section.exercises` | `assets/drill.js`, grouped by `assets/app.js` |
| Glossary | one row per term in `glossary.html`; pages link a term with `a.gl`, at its first use only | popups in `assets/app.js` |
| Source links | `a.src` with `data-src`, `data-lines` and `data-expect`, and no `href` | relative links built by `assets/app.js` |
| Code excerpts | `figure.code` with the same attributes, and the cited lines pasted verbatim into its `pre` | `assets/app.js` |
| Navigation | `LAYERS` and `DEEP` at the top of `assets/app.js` | header, side index and pager in `assets/app.js` |

`assets/theme.js` runs in each page's `<head>`. It applies the saved theme before first paint, and it adds the `js` class that the CSS uses, so that captions and answers stay visible without JavaScript.

## Source links

Every claim was checked against the code in this repository, and every source link points into it. A source link stores only where it points:

```html
<a class="src" data-src="core/engine/runtime2/.../EngineResult.kt" data-lines="954-967" data-expect="fun freeze()">freeze</a>
```

`data-src` is relative to the repository root. `data-expect` lists identifiers, separated by `||`, that must appear in the cited lines. For a Markdown target, `data-anchor` names a heading. When the page loads, `app.js` turns `data-src` into a relative link (`SOURCE_ROOT` climbs from this folder to the repository root) and puts the cited lines in the link's tooltip. The links stay correct wherever the repository is checked out. Never write the `href` by hand: the validator rejects hard-coded repository links.

## Validating

`tools/validate.py` is a read-only check, like a linter. Run it before every commit. It never writes files, so there's nothing generated to commit. Fix whatever it reports by editing the pages.

```bash
python3 -I tools/validate.py
```

It checks the pages against the code in the same checkout, so it needs no arguments:

- Source checks: every cited line range exists and contains its `data-expect` text, Markdown anchors match a heading, and every code excerpt matches its lines exactly.
- Site checks: internal links and anchors, `a.gl` glossary ids, scene timing (beats sorted and unique, no time past `data-duration`), concept-graph references, `data-hl` within the excerpt, that no page hard-codes a repository link, and that every chapter and deep-dive page is in the nav exactly once.

## When the code changes

A change to Runtime2 can move or invalidate what the tour cites. Run the validator after such a change:

1. For each reported link, find the cited code and update `data-lines` (and `data-expect`, if the code was renamed). If the claim no longer holds, fix the prose too.
2. For each reported code excerpt, paste the new lines verbatim into its `pre`, and update `data-lines`, `data-start` and the line range in its caption.
3. Rerun until it prints `OK`.

The validator catches moved and renamed code, not changed behavior: when a change alters what a cited passage does, reread the claims that cite it.

## Ported pages

The Motivation, Field selection and Demand closure deep dives are adapted from Andy Marek's Viaduct guide. His guide generated them from executable examples. In this repo they are ordinary hand-maintained pages: there is no generator to rerun, and his example runner isn't here.

- Edit them like any other page, and keep the author's wording unless a claim needs correcting.
- Their claims were checked against the code they link to, like the rest of the site. The recorded test results (counts, the "What execution confirms" tables and the step traces) come from his runs at an earlier revision and weren't re-run here. When the code they exercise changes, re-check those results by reading it.
- On the demand-closure pages, each scene's `ol.beats` captions are the only copy of the step text. `dc.js` builds the clickable step list beside the scene from them.

## Adding a page

1. Create the HTML from a sibling page, so it loads `theme.js`, `style.css` and `app.js` with the right relative paths.
2. Add it to `LAYERS` (chapters) or `DEEP` (deep dives) in `assets/app.js`. That drives the header, side index and pager.
3. List it on `deep-dives/index.html` and in this README.
4. Run the validator.
