(function () {
  "use strict";

  // The site lives in core/engine/runtime2/impldocs/website, so this climbs from the site root to the repository root.
  // Links stay relative so they follow the code wherever the repository is checked out; tools/validate.py checks them.
  const SOURCE_ROOT = "../../../../../";

  const LAYERS = [
    { n: 0, file: "index.html", title: "Viaduct's Runtime2 engine", short: "Overview" },
    { n: 1, file: "chapters/1-actors.html", title: "The actors", short: "Actors" },
    { n: 2, file: "chapters/2-demand-and-supply.html", title: "Demand and supply", short: "Demand and supply" },
    { n: 3, file: "chapters/3-a-request.html", title: "A request, end to end", short: "A request" },
    { n: 4, file: "chapters/4-exercises.html", title: "Exercises", short: "Exercises" },
  ];
  const G_START = "Start here", G_TWIST = "Common scenarios", G_MECH = "Mechanisms in depth", G_BEYOND = "Code and special cases";
  const DEEP = [
    { file: "deep-dives/index.html", title: "Deep dives" },
    { file: "deep-dives/motivation/index.html", title: "Motivation", group: G_START, pages: [
      { file: "deep-dives/motivation/index.html", title: "Motivation" },
      { file: "deep-dives/motivation/an-opinion.html", title: "Taking an opinion" },
      { file: "deep-dives/motivation/features.html", title: "What each feature asks" },
      { file: "deep-dives/motivation/consequences.html", title: "Three consequences" },
    ] },
    { file: "deep-dives/nested-inputs-and-node-references.html", title: "Nested inputs and node references", group: G_TWIST },
    { file: "deep-dives/arguments-and-aliases.html", title: "Arguments and aliases", group: G_TWIST },
    { file: "deep-dives/selectivity.html", title: "Selectivity", group: G_TWIST },
    { file: "deep-dives/access-checks.html", title: "Access checks", group: G_TWIST },
    { file: "deep-dives/skip-include.html", title: "@skip and @include", group: G_TWIST },
    { file: "deep-dives/field-selection/index.html", title: "Field selection forests", group: G_MECH, pages: [
      { file: "deep-dives/field-selection/index.html", title: "Overview" },
      { file: "deep-dives/field-selection/trees-and-keys.html", title: "Trees, aliases and arguments" },
      { file: "deep-dives/field-selection/fragments-and-guards.html", title: "Fragments and guards" },
      { file: "deep-dives/field-selection/types.html", title: "Abstract types" },
      { file: "deep-dives/field-selection/mutations-and-combined.html", title: "Mutations and the full picture" },
    ] },
    { file: "deep-dives/demand-closure/index.html", title: "Demand closure", group: G_MECH, pages: [
      { file: "deep-dives/demand-closure/index.html", title: "Overview" },
      { file: "deep-dives/demand-closure/hidden-chains.html", title: "Hidden dependency chains" },
      { file: "deep-dives/demand-closure/inputs.html", title: "Where inputs come from" },
      { file: "deep-dives/demand-closure/checks.html", title: "Checks and ancestors" },
      { file: "deep-dives/demand-closure/scale.html", title: "Scale and difficulty" },
    ] },
    { file: "deep-dives/into-the-code.html", title: "Into the code", group: G_BEYOND },
    { file: "deep-dives/mutations.html", title: "Mutations", group: G_BEYOND },
    { file: "deep-dives/cycles.html", title: "Cycles", group: G_BEYOND },
    { file: "deep-dives/type-hierarchies.html", title: "Type hierarchies", group: G_BEYOND },
    { file: "deep-dives/reference-resolver-families.html", title: "Reference resolver families", group: G_BEYOND },
  ];
  const DEEP_PAGES = DEEP.flatMap((d) => (d.pages ? d.pages.map((p) => ({ ...p, topic: d })) : [{ ...d, topic: d }]));

  const label = (l) => (l.n === 0 ? "Overview" : l.n) + " · " + l.title;

  const store = {
    get(k, d) { try { const v = localStorage.getItem("r2v2:" + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem("r2v2:" + k, JSON.stringify(v)); } catch (e) { /* storage unavailable */ } },
  };
  const here = +(document.body.dataset.layer || 0);
  const path = location.pathname.replace(/\/$/, "/index.html");
  const inDeep = /\/deep-dives\//.test(path);
  const inSub = /\/deep-dives\/[^/]+\/[^/]*$/.test(path);
  const base = inSub ? "../../" : inDeep || /\/chapters\//.test(path) ? "../" : "";
  const isGlossary = path.endsWith("/glossary.html");

  // Follows the OS setting; the toggle stores an override only while it differs from the OS. theme.js applies it before first paint.
  const systemDark = matchMedia("(prefers-color-scheme: dark)");
  const systemTheme = () => (systemDark.matches ? "dark" : "light");
  const effectiveTheme = () => document.documentElement.dataset.theme || systemTheme();
  function applyTheme(t) {
    if (t === "dark" || t === "light") document.documentElement.dataset.theme = t;
    else delete document.documentElement.dataset.theme;
  }
  function toggleTheme() {
    const next = effectiveTheme() === "dark" ? "light" : "dark";
    store.set("theme", next === systemTheme() ? null : next);
    applyTheme(next === systemTheme() ? null : next);
  }

  function chrome() {
    const top = document.createElement("header");
    top.className = "topbar";
    top.innerHTML =
      `<a class="brand" href="${base}index.html"><img class="logo" src="${base}assets/viaduct-logo.svg" alt=""><span class="name">Viaduct</span><span class="sub">Runtime2 engine</span></a>` +
      `<nav class="alt" aria-label="Layers">` +
      LAYERS.map((l) => (l.n ? `<b class="sep${l.n <= here ? " past" : ""}"></b>` : "") + `<a href="${base}${l.file}" class="${l.n === here ? "on" : l.n < here ? "past" : ""}" title="${label(l)}"><i></i><span>${l.short}</span></a>`).join("") +
      `</nav>` +
      `<a class="gloss${inDeep ? " on" : ""}" href="${base}deep-dives/index.html">Deep dives</a>` +
      `<a class="gloss${isGlossary ? " on" : ""}" href="${base}glossary.html">Glossary</a>` +
      `<button class="theme" aria-label="Toggle light/dark">◐</button>`;
    document.body.prepend(top);
    top.querySelector(".theme").onclick = toggleTheme;
    const alt = top.querySelector(".alt"), onStep = alt.querySelector("a.on");
    const centerStep = () => {
      if (!onStep || alt.scrollWidth <= alt.clientWidth) return;
      const ar = alt.getBoundingClientRect(), r = onStep.getBoundingClientRect();
      alt.scrollLeft += r.left - ar.left - (ar.width - r.width) / 2;
    };
    centerStep();
    addEventListener("resize", centerStep, { passive: true });

    const main = document.querySelector("main");
    if (!main) return;
    const pg = document.createElement("nav");
    pg.className = "pager";
    if (inDeep) {
      const i = DEEP_PAGES.findIndex((d) => path.endsWith("/" + d.file));
      if (i < 0) return;
      const cur = DEEP_PAGES[i], prev = DEEP_PAGES[i - 1], next = DEEP_PAGES[i + 1];
      const nm = (p) => (p.topic.pages && p.topic !== cur.topic ? (p.file === p.topic.file ? p.topic.title : p.topic.title + " · " + p.title) : p.title);
      const same = (p) => p && p.topic === cur.topic && cur.topic.pages;
      pg.innerHTML =
        (prev ? `<a href="${base}${prev.file}"><small>← ${same(prev) ? "previous part" : "previous"}</small>${nm(prev)}</a>` : `<span></span>`) +
        (next ? `<a class="next" href="${base}${next.file}"><small>${same(next) ? "next part →" : "next deep dive →"}</small>${nm(next)}</a>` : `<span></span>`);
      if (i > 0) sideIndex(cur);
    } else {
      const at = LAYERS.findIndex((l) => path.endsWith("/" + l.file));
      if (at < 0 || at !== here) return;
      const prev = LAYERS[here - 1];
      const next = LAYERS[here + 1] || (here === LAYERS.length - 1 ? { n: "+", ...DEEP[0] } : null);
      pg.innerHTML =
        (prev ? `<a href="${base}${prev.file}"><small>← Previous</small>${label(prev)}</a>` : `<span></span>`) +
        (next ? `<a class="next" href="${base}${next.file}"><small>${next.n === "+" ? "More →" : "Next →"}</small>${next.n === "+" ? next.title : label(next)}</a>` : `<span></span>`);
      mainIndex();
    }
    main.appendChild(pg);
  }

  function pageSections() {
    const main = document.querySelector("main");
    return [...main.querySelectorAll("h2")].filter((h) => !h.closest("figure, .drill, .dd-start, article.ex")).map((h) => {
      const sec = h.parentElement.matches("section[id]") && h.parentElement.firstElementChild === h ? h.parentElement : null;
      if (!sec && !h.id) h.id = h.textContent.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
      return { h, target: sec || h };
    });
  }
  function mountIndex(label, html, heads, title) {
    const nav = document.createElement("nav");
    nav.className = "side-index";
    nav.setAttribute("aria-label", label);
    nav.innerHTML = html +
      `<details class="side-legend"><summary>Color key</summary>` +
      `<ul><li class="c1"><b>structure</b> registry, keys, packages and other fixed machinery</li><li class="c2"><b>values</b> results and cells</li><li class="c3"><b>demand</b></li><li class="c4"><b>tasks</b></li><li class="c5"><b>checks</b></li></ul></details>`;
    document.body.classList.add("has-index");
    document.body.appendChild(nav);
    const mob = document.createElement("details");
    mob.className = "mob-index";
    mob.innerHTML = `<summary>Contents<small>${title}</small></summary>`;
    const inline = nav.cloneNode(true);
    inline.classList.add("inline");
    inline.setAttribute("aria-label", label + " contents");
    mob.appendChild(inline);
    mob.addEventListener("click", (e) => { if (e.target.closest("a[href^='#']")) mob.open = false; });
    const main = document.querySelector("main");
    main.prepend(mob);
    const links = new Map([...nav.querySelectorAll(".secs a")].map((a) => [a.getAttribute("href").slice(1), a]));
    if (!links.size) return;
    const io = new IntersectionObserver((es) => es.forEach((e) => {
      if (e.isIntersecting) { links.forEach((a) => a.classList.remove("on")); const a = links.get(e.target.id); if (a) a.classList.add("on"); }
    }), { rootMargin: "-20% 0px -70% 0px" });
    heads.forEach(({ target }) => io.observe(target));
  }
  const secList = (heads) => (heads.length ? `<ol class="secs">${heads.map(({ h, target }) => `<li><a href="#${target.id}">${h.textContent}</a></li>`).join("")}</ol>` : "");

  function mainIndex() {
    const heads = pageSections();
    const items = LAYERS.map((l) => {
      const on = l.n === here;
      return `<li class="topic${on ? " open on" : ""}"><a href="${base}${l.file}">${l.n === 0 ? "Overview" : l.n + " · " + l.title}</a>${on ? secList(heads) : ""}</li>`;
    }).join("");
    mountIndex("Chapters", `<div class="group start">Chapters</div><ol>${items}</ol><div class="group">More</div><ol><li class="topic"><a href="${base}deep-dives/index.html">Deep dives</a></li><li class="topic"><a href="${base}glossary.html">Glossary</a></li></ol>`, heads, LAYERS[here] ? label(LAYERS[here]) : "");
  }

  function sideIndex(cur) {
    const heads = pageSections();
    const secs = (here) => (here ? secList(heads) : "");
    const t = cur.topic;
    let html = "";
    if (t !== DEEP[0]) {
      const inner = t.pages
        ? `<ol class="parts">${t.pages.map((p) => { const on = p.file === cur.file; return `<li class="${on ? "on" : ""}"><a href="${base}${p.file}">${p.title}</a>${secs(on)}</li>`; }).join("")}</ol>`
        : secs(true);
      html = `<div class="group cur">${t.group}</div><ol><li class="topic open${t.pages ? "" : " on"}"><a href="${base}${t.file}">${t.title}</a>${inner}</li></ol>`;
    }
    mountIndex("Deep dives", `<a class="up${cur.topic === DEEP[0] ? " on" : ""}" href="${base}deep-dives/index.html">All deep dives</a>` + html, heads, cur.topic === DEEP[0] ? "Deep dives" : cur.topic.pages && cur.file !== cur.topic.file ? cur.topic.title + " · " + cur.title : cur.topic.title);
  }

  function reveal() {
    const q = new URLSearchParams(location.search);
    if (q.has("static") || q.has("t")) return;
    const io = new IntersectionObserver((es) => es.forEach((e) => {
      if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); }
    }), { threshold: 0.08 });
    document.querySelectorAll("main > section > *:not(figure.scene):not(.cgraph)").forEach((el) => { el.classList.add("reveal"); io.observe(el); });
  }

  // ---------- code ----------
  function splitHighlighted(htmlStr) {
    const out = []; const open = []; let cur = ""; let m;
    const re = /(<span[^>]*>)|(<\/span>)|(\n)|([^<\n]+|<)/g;
    while ((m = re.exec(htmlStr))) {
      if (m[1]) { open.push(m[1]); cur += m[1]; }
      else if (m[2]) { open.pop(); cur += m[2]; }
      else if (m[3]) { cur += "</span>".repeat(open.length); out.push(cur); cur = open.join(""); }
      else cur += m[4];
    }
    out.push(cur);
    return out;
  }
  function parseHl(s) {
    const set = new Set();
    (s || "").split(",").forEach((p) => {
      const m = p.trim().match(/^(\d+)(?:-(\d+))?$/);
      if (m) for (let i = +m[1]; i <= +(m[2] || m[1]); i++) set.add(i);
    });
    return set;
  }
  function renderCode(pre) {
    const code = pre.querySelector("code");
    if (!code || pre.dataset.rendered) return;
    pre.dataset.rendered = "1";
    let h = code.innerHTML;
    if (window.hljs) {
      const lang = (code.className.match(/language-(\S+)/) || [])[1];
      try { if (lang && hljs.getLanguage(lang)) h = hljs.highlight(code.textContent, { language: lang }).value; } catch (e) { /* plain */ }
    }
    const start = +(pre.dataset.start || 1);
    const fig = pre.closest("figure");
    const hl = parseHl(fig && fig.dataset.hl);
    code.innerHTML = splitHighlighted(h).map((l, i) => `<span class="ln${hl.has(start + i) ? " hl" : ""}" data-n="${start + i}">${l || " "}</span>`).join("");
  }

  // ---------- glossary popups ----------
  let glossary = null;
  function loadGlossary() {
    if (!glossary) glossary = fetch(`${base}glossary.html`).then((r) => r.text()).then((t) => {
      const doc = new DOMParser().parseFromString(t, "text/html");
      const map = new Map();
      doc.querySelectorAll("tr[id]").forEach((tr) => {
        const td = tr.querySelectorAll("td");
        if (td.length < 3) return;
        const e = { name: td[0].textContent.trim(), def: td[2].innerHTML };
        map.set(tr.id, e);
        tr.querySelectorAll("[id]").forEach((a) => map.set(a.id, e));
      });
      return map;
    }).catch(() => new Map());
    return glossary;
  }
  function initGlossaryPops() {
    if (isGlossary) return;
    const SEL = 'a.gl[href*="glossary.html#"]';
    let pop = null, timer = 0, hideTimer = 0, cur = null;
    const hide = () => { clearTimeout(timer); clearTimeout(hideTimer); cur = null; if (pop) { pop.remove(); pop = null; } };
    const hideSoon = () => { clearTimeout(hideTimer); hideTimer = setTimeout(hide, 250); };
    const show = (a) => {
      clearTimeout(hideTimer);
      if (a === cur) return;
      clearTimeout(timer);
      cur = a;
      const id = a.getAttribute("href").split("#")[1];
      timer = setTimeout(() => loadGlossary().then((m) => {
        const e = m.get(id);
        if (!e || cur !== a || !a.isConnected) return;
        if (pop) pop.remove();
        pop = document.createElement("div");
        pop.className = "term-pop gl-pop";
        pop.setAttribute("role", "tooltip");
        pop.innerHTML = `<b>${e.name}</b>${e.def.replace(/<a\b[^>]*>|<\/a>/g, "")}`;
        pop.addEventListener("mouseenter", () => clearTimeout(hideTimer));
        pop.addEventListener("mouseleave", (ev) => { if (!(cur && cur.contains(ev.relatedTarget))) hideSoon(); });
        document.body.appendChild(pop);
        const r = a.getBoundingClientRect();
        pop.style.left = Math.max(8, Math.min(scrollX + r.left, scrollX + innerWidth - pop.offsetWidth - 8)) + "px";
        const below = r.bottom + 6 + pop.offsetHeight < innerHeight;
        pop.style.top = (below ? scrollY + r.bottom + 6 : scrollY + r.top - pop.offsetHeight - 6) + "px";
      }), 150);
    };
    const over = (e) => { const a = e.target.closest && e.target.closest(SEL); if (a) show(a); };
    const out = (e) => {
      const a = e.target.closest && e.target.closest(SEL);
      if (!a || a.contains(e.relatedTarget) || (pop && pop.contains(e.relatedTarget))) return;
      if (e.type === "focusout" || !pop) hide(); else hideSoon();
    };
    document.addEventListener("mouseover", over);
    document.addEventListener("mouseout", out);
    document.addEventListener("focusin", over);
    document.addEventListener("focusout", out);
    document.addEventListener("keydown", (e) => { if (e.key === "Escape" && (pop || cur)) hide(); });
    addEventListener("scroll", hide, { passive: true });
  }

  function groupExercises() {
    const secs = [...document.querySelectorAll("main section.exercises")];
    const blocks = [];
    secs.forEach((sec) => {
      let cur = null;
      [...sec.children].forEach((el) => {
        if (el.matches("h2")) return;
        const starts = el.matches("h3") || (el.matches(".drill") && (!cur || cur.dataset.titled === "0" && cur.querySelector(".drill")));
        if (starts) {
          cur = document.createElement("article");
          cur.className = "ex";
          cur.dataset.titled = el.matches("h3") ? "1" : "0";
          sec.insertBefore(cur, el);
          blocks.push(cur);
        }
        if (cur) cur.appendChild(el);
      });
    });
    blocks.forEach((b, i) => {
      const h = document.createElement("div");
      h.className = "ex-head";
      h.innerHTML = `<span class="ex-n">${blocks.length > 1 ? `Exercise ${i + 1} of ${blocks.length}` : "Exercise"}</span>`;
      const h3 = b.querySelector(":scope > h3");
      if (h3) h.appendChild(h3);
      b.prepend(h);
      const update = () => b.classList.toggle("done", [...b.querySelectorAll(".drill")].every((d) => d.classList.contains("solved")));
      new MutationObserver(update).observe(b, { subtree: true, attributes: true, attributeFilter: ["class"] });
    });
  }

  function highlightGraphQL(pre) {
    const walker = document.createTreeWalker(pre, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) if (!walker.currentNode.parentElement.closest("span.c, span.d")) nodes.push(walker.currentNode);
    const re = /\b(type|query|mutation|fragment|implements|interface|input|enum|union|extend|on)\b|(:\s*\[?)([A-Z]\w*)|("[^"\n]*")|(\$\w+)/g;
    nodes.forEach((n) => {
      const t = n.textContent;
      if (!re.test(t)) return;
      re.lastIndex = 0;
      const esc = (x) => x.replace(/&/g, "&amp;").replace(/</g, "&lt;");
      const span = document.createElement("span");
      span.innerHTML = esc(t).replace(re, (m, kw, pre2, ty, str, vr) =>
        kw ? `<span class="k">${kw}</span>` : ty ? `${pre2}<span class="t">${ty}</span>` : str ? `<span class="s">${str}</span>` : `<span class="v">${vr}</span>`);
      n.replaceWith(...span.childNodes);
    });
  }

  function codeHeaders() {
    document.querySelectorAll("pre.cmd").forEach((pre) => {
      if (pre.closest(".codeblock")) return;
      const first = pre.firstChild;
      let label = "";
      if (first && first.nodeType === 1 && first.matches("span.c") && pre.firstChild.nextSibling && /^\n/.test(pre.firstChild.nextSibling.textContent || "")) {
        label = first.textContent.replace(/^(#|\/\/)\s*/, "");
        first.remove();
        const nx = pre.firstChild;
        if (nx && nx.nodeType === 3) nx.textContent = nx.textContent.replace(/^\n/, "");
      }
      const text = pre.textContent;
      const LANGS = { kotlin: "Kotlin", kt: "Kotlin", graphql: "GraphQL", gql: "GraphQL" };
      const given = (pre.dataset.lang || "").trim();
      const lang = given ? LANGS[given.toLowerCase()] || given
        : /\.kt\b|^\/\//.test(label) || /\bfun |\bval |override /.test(text) ? "Kotlin"
        : /\b(type|query|mutation|fragment|interface|input)\b[^\n]*\{/.test(text) ? "GraphQL" : "";
      if (lang === "GraphQL") highlightGraphQL(pre);
      const wrap = document.createElement("div");
      wrap.className = "codeblock";
      pre.before(wrap);
      const head = document.createElement("div");
      head.className = "code-head";
      head.innerHTML = `<span class="lbl"></span>${lang ? `<span class="lang">${lang}</span>` : ""}<button class="copy" type="button" aria-label="Copy code">Copy</button>`;
      head.querySelector(".lbl").textContent = label ? label.charAt(0).toUpperCase() + label.slice(1) : (lang || "Code");
      head.querySelector(".copy").onclick = (e) => {
        const b = e.currentTarget;
        const flash = (t) => { b.textContent = t; setTimeout(() => (b.textContent = "Copy"), 1500); };
        const fallback = () => {
          const sel = getSelection(), r = document.createRange();
          r.selectNodeContents(pre); sel.removeAllRanges(); sel.addRange(r);
          let ok = false;
          try { ok = document.execCommand("copy"); } catch (err) { /* unsupported */ }
          if (ok) { sel.removeAllRanges(); flash("Copied"); } else flash("Selected: press Ctrl+C");
        };
        try { if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(pre.textContent).then(() => flash("Copied"), fallback); return; } } catch (err) { /* clipboard unavailable */ }
        fallback();
      };
      wrap.append(head, pre);
    });
  }

  function sourceHref(el) {
    return `${base}${SOURCE_ROOT}${el.dataset.src}` + (el.dataset.anchor ? `#${el.dataset.anchor}` : "");
  }

  // A relative file link can't carry a line anchor, so the cited lines go in the tooltip.
  function sourceTitle(el) {
    const m = /^(\d+)(?:-(\d+))?$/.exec((el.dataset.lines || "").trim());
    if (!m) return el.dataset.src;
    return `${el.dataset.src}, ${m[2] && m[2] !== m[1] ? `lines ${m[1]}–${m[2]}` : `line ${m[1]}`}`;
  }

  function sourceLinks() {
    const set = (a, from) => { a.href = sourceHref(from); a.title = sourceTitle(from); a.target = "_blank"; a.rel = "noopener"; };
    document.querySelectorAll("a.src[data-src]").forEach((a) => set(a, a));
    document.querySelectorAll("figure.code[data-src] > figcaption > a").forEach((a) => set(a, a.closest("figure")));
  }

  function boot() {
    sourceLinks();
    codeHeaders();
    groupExercises();
    chrome();
    document.querySelectorAll("pre.src").forEach(renderCode);
    document.querySelectorAll(".answer").forEach((a) => { const d = a.previousElementSibling; if (d && d.matches(".drill")) a.hidden = true; });
    initGlossaryPops();
    reveal();
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
