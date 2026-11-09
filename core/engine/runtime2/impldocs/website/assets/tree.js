/* Hierarchy trees.
 *
 * <div class="htree"><ul>
 *   <li data-kind="sealed interface"><span class="ht-name">EngineResult</span>
 *       <div class="ht-doc">role, with <a class="src" ...>source</a></div>
 *       <ul>...children...</ul></li>
 * </ul></div>
 *
 * Branches collapse and expand; a node's doc opens on click; the tree grows in, level by level.
 */
(function () {
  "use strict";
  const q = new URLSearchParams(location.search);
  const STATIC = q.has("static") || q.has("t");
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;

  function setOpen(li, tog, open) {
    li.classList.toggle("closed", !open);
    tog.textContent = open ? "▾" : "▸";
    tog.setAttribute("aria-expanded", String(open));
  }
  function syncDoc(name, li) {
    name.setAttribute("aria-expanded", String(li.classList.contains("doc-open") || !!li.closest(".htree.all-docs")));
  }

  function init(root) {
    const lis = [...root.querySelectorAll("li")];
    lis.forEach((li) => {
      const name = li.querySelector(":scope > .ht-name");
      if (!name) return;
      const depth = (() => { let d = 0, p = li.parentElement; while (p && p !== root) { if (p.tagName === "UL") d++; p = p.parentElement; } return d - 1; })();
      li.style.setProperty("--d", depth);
      const row = document.createElement("div");
      row.className = "ht-row";
      li.prepend(row);
      const kids = li.querySelector(":scope > ul");
      let tog;
      if (kids) {
        tog = document.createElement("button");
        tog.type = "button";
        tog.className = "ht-tog";
        tog.setAttribute("aria-label", "Toggle children");
        setOpen(li, tog, true);
        tog.onclick = () => setOpen(li, tog, li.classList.contains("closed"));
        const n = kids.querySelectorAll(":scope > li").length;
        name.dataset.count = n;
      } else {
        tog = document.createElement("span");
        tog.className = "ht-tog leaf";
        tog.setAttribute("aria-hidden", "true");
      }
      row.appendChild(tog);
      row.appendChild(name);
      if (li.dataset.kind) {
        const k = document.createElement("span");
        k.className = "ht-kind k-" + li.dataset.kind.replace(/\s+/g, "-");
        k.textContent = li.dataset.kind;
        row.appendChild(k);
      }
      const doc = li.querySelector(":scope > .ht-doc");
      if (doc) {
        name.tabIndex = 0;
        name.classList.add("has-doc");
        name.setAttribute("role", "button");
        name.setAttribute("aria-expanded", "false");
        const open = () => { li.classList.toggle("doc-open"); syncDoc(name, li); };
        name.onclick = open;
        name.onkeydown = (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); open(); } };
      }
    });
    const bar = document.createElement("div");
    bar.className = "ht-bar";
    bar.innerHTML = `<button class="btn" data-a="open">Expand all</button><button class="btn" data-a="close">Collapse</button><button class="btn" data-a="docs">Show all notes</button>`;
    root.prepend(bar);
    bar.onclick = (e) => {
      const a = e.target.dataset.a;
      const togOf = (li) => li.querySelector(":scope > .ht-row > button.ht-tog");
      if (a === "open") lis.forEach((li) => { const t = togOf(li); if (t) setOpen(li, t, true); });
      if (a === "close") lis.forEach((li) => { const t = togOf(li); if (t && +getComputedStyle(li).getPropertyValue("--d") >= 1) setOpen(li, t, false); });
      if (a === "docs") {
        const on = !root.classList.contains("all-docs"); root.classList.toggle("all-docs", on); e.target.textContent = on ? "Hide notes" : "Show all notes";
        lis.forEach((li) => { const n = li.querySelector(":scope > .ht-row > .ht-name.has-doc"); if (n) syncDoc(n, li); });
      }
    };
    if (STATIC || reduced) { root.classList.add("grown"); return; }
    const io = new IntersectionObserver((es) => es.forEach((en) => {
      if (en.isIntersecting) { root.classList.add("grown"); io.disconnect(); }
    }), { threshold: 0.15 });
    io.observe(root);
  }
  function boot() { document.querySelectorAll(".htree").forEach(init); }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
