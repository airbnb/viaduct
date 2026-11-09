/* Interactive concept graph.
 *
 * <figure class="cgraph" data-w="960" data-h="560">
 *   <div class="cg-node" data-id="oer" data-x="400" data-y="200" data-group="1" data-label="OER" data-sub="ObjectEngineResult">definition HTML</div>
 *   <div class="cg-edge" data-from="a" data-to="b" data-label="creates" [data-bend="40"]></div>
 *   <ol class="cg-tour"><li data-focus="oer">caption</li></ol>
 * </figure>
 *
 * Nodes are positioned by the author (x, y = center). Clicking a node focuses it: neighbors stay lit,
 * everything else dims, and tokens flow along its edges in their real direction.
 */
(function () {
  "use strict";
  const NS = "http://www.w3.org/2000/svg";
  const q = new URLSearchParams(location.search);
  const STATIC = q.has("static") || q.has("t");
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const mk = (tag, attrs, parent) => {
    const e = document.createElementNS(NS, tag);
    for (const k in attrs) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  };
  const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  function init(fig) {
    const W = +(fig.dataset.w || 960), H = +(fig.dataset.h || 560);
    const nodes = [...fig.querySelectorAll(":scope > .cg-node")].map((d) => ({
      id: d.dataset.id, x: +d.dataset.x, y: +d.dataset.y, g: d.dataset.group || "1",
      label: d.dataset.label, sub: d.dataset.sub || "", def: d.innerHTML.trim(), w: 0, h: 0,
    }));
    const byId = Object.fromEntries(nodes.map((n) => [n.id, n]));
    const edges = [...fig.querySelectorAll(":scope > .cg-edge")].map((d, i) => ({
      i, from: d.dataset.from, to: d.dataset.to, label: d.dataset.label || "", bend: +(d.dataset.bend || 0),
    })).filter((e) => byId[e.from] && byId[e.to]);
    const tour = [...fig.querySelectorAll(":scope > ol.cg-tour > li")].map((li) => ({ focus: li.dataset.focus, html: li.innerHTML }));
    fig.querySelectorAll(":scope > .cg-node, :scope > .cg-edge, :scope > ol.cg-tour").forEach((e) => (e.hidden = true));

    const wrap = document.createElement("div");
    wrap.className = "cg-wrap";
    const stage = document.createElement("div");
    stage.className = "cg-stage";
    const capEl = fig.querySelector(":scope > figcaption");
    const name = (capEl && capEl.textContent.trim()) || fig.getAttribute("aria-label") || "Concept graph";
    const svg = mk("svg", { viewBox: `0 0 ${W} ${H}`, role: "group", "aria-label": name });
    stage.appendChild(svg);
    const panel = document.createElement("aside");
    panel.className = "cg-panel";
    wrap.append(stage, panel);
    fig.prepend(wrap);

    const defs = mk("defs", {}, svg);
    const marker = mk("marker", { id: `ah-${fig.id || Math.random().toString(36).slice(2)}`, viewBox: "0 0 10 10", refX: "9", refY: "5", markerWidth: "7", markerHeight: "7", orient: "auto-start-reverse" }, defs);
    mk("path", { d: "M0,0 L10,5 L0,10 z", class: "cg-ah" }, marker);
    const gEdges = mk("g", { class: "cg-edges" }, svg);
    const gTok = mk("g", { class: "cg-tokens" }, svg);
    const gNodes = mk("g", { class: "cg-nodes" }, svg);
    const gLabels = mk("g", { class: "cg-labels" }, svg);

    nodes.forEach((n, k) => {
      const g = mk("g", { class: `cg-n g${n.g}`, tabindex: "0", role: "button", "aria-label": n.label }, gNodes);
      const inner = mk("g", {}, g);
      const rect = mk("rect", { rx: "10" }, inner);
      const t1 = mk("text", { class: "lbl", "text-anchor": "middle" }, inner);
      t1.textContent = n.label;
      let t2 = null;
      if (n.sub) { t2 = mk("text", { class: "sub", "text-anchor": "middle" }, inner); t2.textContent = n.sub; }
      n.el = g; n.inner = inner; n.rect = rect; n.t1 = t1; n.t2 = t2; n.k = k;
    });
    // size boxes after text is in the DOM
    nodes.forEach((n) => {
      const w1 = n.t1.getComputedTextLength ? n.t1.getComputedTextLength() : n.label.length * 8;
      const w2 = n.t2 ? n.t2.getComputedTextLength() : 0;
      n.w = Math.max(w1, w2) + 28; n.h = n.t2 ? 50 : 36;
      n.rect.setAttribute("x", n.x - n.w / 2); n.rect.setAttribute("y", n.y - n.h / 2);
      n.rect.setAttribute("width", n.w); n.rect.setAttribute("height", n.h);
      n.t1.setAttribute("x", n.x); n.t1.setAttribute("y", n.y + (n.t2 ? -4 : 5));
      if (n.t2) { n.t2.setAttribute("x", n.x); n.t2.setAttribute("y", n.y + 14); }
      n.inner.style.transformBox = "fill-box"; n.inner.style.transformOrigin = "center";
    });

    // Edges leave and enter boxes square to the side they cross: the side pair is chosen by the wider
    // gap between the boxes; boxes that overlap along that side get one straight edge, and data-bend shifts
    // both ports along their sides (to separate parallel edges).
    const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
    function ports(a, b, horiz, shift) {
      const [u, v, su, sv, inset] = horiz ? ["y", "x", "h", "w", 8] : ["x", "y", "w", "h", 16];
      const lo = Math.max(a[u] - a[su] / 2, b[u] - b[su] / 2) + inset, hi = Math.min(a[u] + a[su] / 2, b[u] + b[su] / 2) - inset;
      const along = (n, o) => lo <= hi ? clamp((a[u] + b[u]) / 2 + shift, lo, hi)
        : n[u] + clamp((o[u] - n[u]) * 0.35 + shift, -(n[su] / 2 - inset), n[su] / 2 - inset);
      const end = (n, o) => {
        const s = Math.sign(o[v] - n[v]) || 1;
        return { [u]: along(n, o), [v]: n[v] + s * (n[sv] / 2 + 4), nx: horiz ? s : 0, ny: horiz ? 0 : s };
      };
      return [end(a, b), end(b, a)];
    }
    edges.forEach((e) => {
      const a = byId[e.from], b = byId[e.to];
      const gx = Math.abs(b.x - a.x) - (a.w + b.w) / 2, gy = Math.abs(b.y - a.y) - (a.h + b.h) / 2;
      const horiz = gx > 0 && gx > gy;
      const len = Math.hypot(b.x - a.x, b.y - a.y) || 1;
      const shift = e.bend * 0.4 * (horiz ? (b.x - a.x) / len : -(b.y - a.y) / len);
      const [P, Q] = ports(a, b, horiz, shift);
      const span = horiz ? Math.abs(Q.x - P.x) : Math.abs(Q.y - P.y);
      const lat = horiz ? Math.abs(Q.y - P.y) : Math.abs(Q.x - P.x);
      // longer handles when a short edge also steps sideways keep the curve straight under the head
      const k = Math.max(28, Math.min(150, Math.max(span * 0.5, Math.min(span * 0.95, lat * 0.6))));
      const [x1, y1, x2, y2] = [P.x, P.y, Q.x, Q.y];
      const c1x = x1 + P.nx * k, c1y = y1 + P.ny * k, c2x = x2 + Q.nx * k, c2y = y2 + Q.ny * k;
      const g = mk("g", { class: "cg-e" }, gEdges);
      const p = mk("path", { d: `M${x1},${y1} C${c1x},${c1y} ${c2x},${c2y} ${x2},${y2}`, "marker-end": `url(#${marker.id})` }, g);
      const lx = 0.125 * (x1 + x2) + 0.375 * (c1x + c2x), ly = 0.125 * (y1 + y2) + 0.375 * (c1y + c2y);
      // beside vertical edges, above horizontal ones, so the label never sits on a head
      const lb = mk("text", horiz ? { x: lx, y: ly - 9, "text-anchor": "middle", class: "elbl" }
        : { x: lx + 8, y: ly + 4, "text-anchor": "start", class: "elbl" }, gLabels);
      lb.textContent = e.label;
      e.el = g; e.lbl = lb; e.path = p; e.len = p.getTotalLength();
      p.style.strokeDasharray = `${e.len} ${e.len}`;
    });

    // ----- intro build animation -----
    function intro() {
      if (STATIC || reduced) { nodes.forEach((n) => n.el.classList.add("in")); edges.forEach((e) => (e.path.style.strokeDashoffset = 0)); return; }
      const order = [...nodes].sort((a, b) => a.g.localeCompare(b.g) || a.k - b.k);
      order.forEach((n, i) => setTimeout(() => n.el.classList.add("in"), 80 * i));
      const t0 = 80 * order.length;
      edges.forEach((e, i) => {
        e.path.style.strokeDashoffset = e.len;
        // the dash animation doesn't hide markers, so add the head once the line is drawn
        const head = e.path.getAttribute("marker-end");
        e.path.removeAttribute("marker-end");
        setTimeout(() => { e.path.style.transition = "stroke-dashoffset .7s ease"; e.path.style.strokeDashoffset = 0; }, t0 + 60 * i);
        setTimeout(() => e.path.setAttribute("marker-end", head), t0 + 60 * i + 700);
      });
    }
    edges.forEach((e) => (e.path.style.strokeDashoffset = STATIC || reduced ? 0 : e.len));
    let introDone = false;
    const io = new IntersectionObserver((es) => es.forEach((en) => {
      if (en.isIntersecting && !introDone) { introDone = true; intro(); }
    }), { threshold: 0.25 });
    io.observe(fig);

    // ----- tokens flowing along focused edges -----
    let flowRaf = 0, flowEdges = [], onScreen = false;
    const canFlow = () => onScreen && !document.hidden && flowEdges.length;
    function resumeFlow() {
      if (canFlow()) { if (!flowRaf) flowRaf = requestAnimationFrame(flow); }
      else { cancelAnimationFrame(flowRaf); flowRaf = 0; }
    }
    new IntersectionObserver((es) => { onScreen = es[es.length - 1].isIntersecting; resumeFlow(); }).observe(fig);
    document.addEventListener("visibilitychange", resumeFlow);
    function flow(now) {
      if (!canFlow()) { flowRaf = 0; return; }
      gTok.querySelectorAll("circle").forEach((c, j) => {
        const e = flowEdges[j];
        const k = ((now / 1600) + j * 0.37) % 1;
        const pt = e.path.getPointAtLength(k * e.len);
        c.setAttribute("cx", pt.x); c.setAttribute("cy", pt.y);
      });
      flowRaf = requestAnimationFrame(flow);
    }
    function startFlow(list) {
      cancelAnimationFrame(flowRaf); flowRaf = 0;
      gTok.innerHTML = "";
      flowEdges = reduced ? [] : list;
      flowEdges.forEach(() => mk("circle", { r: "4.5" }, gTok));
      resumeFlow();
    }

    // ----- focus -----
    let focused = null;
    const defaultPanel = (tour.length ? `<button class="btn primary cg-tour-btn">▶ Guided tour</button>` : "");
    function linkFor(id) { const n = byId[id]; return `<button class="cg-link g${n.g}" data-go="${id}">${esc(n.label)}</button>`; }
    function focus(id, caption) {
      focused = id;
      fig.classList.toggle("focusing", !!id);
      if (!id) {
        nodes.forEach((n) => n.el.classList.remove("hot", "near"));
        edges.forEach((e) => { e.el.classList.remove("hot"); e.lbl.classList.remove("hot"); });
        startFlow([]);
        setPanel(defaultPanel);
        return;
      }
      const n = byId[id];
      const out = edges.filter((e) => e.from === id), inc = edges.filter((e) => e.to === id);
      const near = new Set([id, ...out.map((e) => e.to), ...inc.map((e) => e.from)]);
      nodes.forEach((m) => { m.el.classList.toggle("hot", m.id === id); m.el.classList.toggle("near", near.has(m.id) && m.id !== id); });
      edges.forEach((e) => { const h = e.from === id || e.to === id; e.el.classList.toggle("hot", h); e.lbl.classList.toggle("hot", h); });
      startFlow([...out, ...inc]);
      setPanel(
        (caption ? `<div class="cg-cap">${caption}</div>` : "") +
        `<h4 class="g${n.g}">${esc(n.label)}${n.sub ? ` <small>${esc(n.sub)}</small>` : ""}</h4>` +
        `<div class="cg-def">${n.def}</div>` +
        (out.length ? `<div class="cg-rel"><b>→</b>${out.map((e) => `<span><i>${esc(e.label)}</i> ${linkFor(e.to)}</span>`).join("")}</div>` : "") +
        (inc.length ? `<div class="cg-rel"><b>←</b>${inc.map((e) => `<span>${linkFor(e.from)} <i>${esc(e.label)}</i></span>`).join("")}</div>` : "") +
        (tourIdx >= 0 ? `<div class="cg-tnav"><button class="btn" data-t="-1">◀</button><span>${tourIdx + 1} / ${tour.length}</span><button class="btn primary" data-t="1">▶</button><button class="btn" data-t="x">✕</button></div>`
          : `<button class="btn cg-clear">✕ Clear</button>`));
    }
    function controlKey(el) {
      if (el.dataset.go) return `[data-go="${el.dataset.go}"]`;
      if (el.dataset.t) return `[data-t="${el.dataset.t}"]`;
      return ["cg-clear", "cg-tour-btn"].filter((c) => el.classList.contains(c)).map((c) => "." + c)[0] || null;
    }
    function setPanel(html) {
      const active = document.activeElement;
      const had = active && panel.contains(active) ? controlKey(active) || "" : null;
      panel.innerHTML = html;
      bindPanel();
      if (had === null) return;
      const target = (had && panel.querySelector(had)) || panel.querySelector('[data-t="1"]') || panel.querySelector("button") || (focused ? byId[focused].el : nodes[0] && nodes[0].el);
      if (target) target.focus({ preventScroll: true });
    }
    let tourIdx = -1;
    function tourGo(i) {
      if (i < 0 || i >= tour.length) { tourIdx = -1; focus(null); return; }
      tourIdx = i;
      focus(tour[i].focus, tour[i].html);
    }
    function bindPanel() {
      panel.querySelectorAll("[data-go]").forEach((b) => (b.onclick = () => { tourIdx = -1; focus(b.dataset.go); }));
      const c = panel.querySelector(".cg-clear"); if (c) c.onclick = () => focus(null);
      const tb = panel.querySelector(".cg-tour-btn"); if (tb) tb.onclick = () => tourGo(0);
      panel.querySelectorAll("[data-t]").forEach((b) => (b.onclick = () => {
        const v = b.dataset.t; tourGo(v === "x" ? -1 : tourIdx + +v);
      }));
    }
    nodes.forEach((n) => {
      const go = () => { tourIdx = -1; focus(focused === n.id ? null : n.id); };
      n.el.addEventListener("click", go);
      n.el.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); go(); } });
    });
    svg.addEventListener("click", (e) => { if (e.target === svg) { tourIdx = -1; focus(null); } });
    focus(null);
  }

  function boot() { document.querySelectorAll("figure.cgraph").forEach(init); }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
