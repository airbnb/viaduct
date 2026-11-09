/* Demand-closure scenes: a figure.scene.dd-scene's ol.beats captions are the only copy of its step text.
 * This builds the hint and the clickable step list before the scene from them, and keeps list and scene in sync.
 * Without JS the captions show as a plain list instead. */
(function () {
const HINT = 'Each step is a recorded Runtime2 event from running this example: an <a class="gl" href="../../glossary.html#orchestrator">orchestrator</a> preparing an <a class="gl" href="../../glossary.html#object-result">object engine result</a> (OER) or a <a class="gl" href="../../glossary.html#resolver-task">resolver task</a> calling its <a class="gl" href="../../glossary.html#resolver">resolver</a>. The numbers match the badges and the step counter in the diagram; click a step to jump to it.';
const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
document.querySelectorAll("figure.scene.dd-scene").forEach((fig, n) => {
  const beats = [...fig.querySelectorAll(":scope > ol.beats > li")];
  if (!beats.length) return;
  const hint = document.createElement("p");
  hint.className = "steps-hint";
  // Glossary links on first use only: drop any the page already linked before this figure.
  const linked = new Set([...document.querySelectorAll("a.gl")]
    .filter((a) => fig.compareDocumentPosition(a) & Node.DOCUMENT_POSITION_PRECEDING)
    .map((a) => a.getAttribute("href")));
  hint.innerHTML = HINT.replace(/<a\b[^>]*href="([^"]*)"[^>]*>(.*?)<\/a>/g, (m, href, text) => (n || linked.has(href) ? text : m));
  const items = beats.map((b, i) => {
    const li = document.createElement("li");
    if (b.dataset.title) {
      const t = document.createElement("b");
      t.className = "st";
      t.textContent = b.dataset.title;
      li.append(t, " ");
    }
    li.append(...[...b.childNodes].map((c) => c.cloneNode(true)));
    li.tabIndex = 0;
    li.setAttribute("role", "button");
    const go = () => {
      if (fig.goBeat) fig.goBeat(i);
      fig.scrollIntoView({ behavior: reduced ? "instant" : "smooth", block: "center" });
    };
    li.addEventListener("click", (e) => { if (!e.target.closest("a")) go(); });
    li.addEventListener("keydown", (e) => {
      if (e.target !== li || (e.key !== "Enter" && e.key !== " ")) return;
      e.preventDefault(); go();
    });
    return li;
  });
  const list = document.createElement("ol");
  list.className = "flow steps";
  list.append(...items);
  fig.before(hint, list);
  fig.addEventListener("beat", (e) => items.forEach((li, i) => li.classList.toggle("on", i === e.detail)));
});
})();
