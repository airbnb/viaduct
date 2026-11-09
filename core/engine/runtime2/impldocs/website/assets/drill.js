// Interactive exercises: choice (single), pick (multi), order (sequence), sort (classify), walk (steps of any type).
// Any wrong option/item/step/chip may carry data-why; it is shown when that element is wrong.
(() => {
  const $$ = (r, s) => [...r.querySelectorAll(s)];
  const shuffle = (a) => { for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };
  const put = (d, el) => { const w = d.querySelector(":scope > .why"); w ? d.insertBefore(el, w) : d.appendChild(el); };
  const fb = (d) => { let f = d.querySelector(":scope > .fb"); if (!f) { f = document.createElement("p"); f.className = "fb"; put(d, f); } return f; };
  const solved = (d) => { d.classList.add("solved"); const w = d.querySelector(":scope > .why"); if (w) w.hidden = false; d.dispatchEvent(new Event("solved")); };
  const why = (els) => { const r = els.map((e) => e.dataset.why).filter(Boolean); return r.length ? "<ul>" + r.map((x) => `<li>${x}</li>`).join("") + "</ul>" : ""; };
  const say = (d, ok, msg) => { const f = fb(d); f.className = "fb " + (ok ? "ok" : "no"); f.innerHTML = msg; };
  const checkBtn = (d, label, fn) => { const b = document.createElement("button"); b.type = "button"; b.className = "check"; b.textContent = label || "Check"; b.onclick = fn; put(d, b); return b; };
  const resetBtn = (d, fn) => { const b = document.createElement("button"); b.type = "button"; b.className = "reset"; b.textContent = "Reset"; b.onclick = fn; put(d, b); };

  // single choice: .drill.choice > .q, button.opt[data-correct][data-why], .why
  function choice(d) {
    $$(d, "button.opt").forEach((b) => {
      b.type = "button";
      b.onclick = () => {
        if (d.classList.contains("solved")) return;
        if (b.dataset.correct === "true") {
          b.classList.add("right"); $$(d, "button.opt").forEach((o) => (o.disabled = true));
          say(d, true, "Correct."); solved(d);
        } else {
          b.classList.remove("wrong"); void b.offsetWidth; b.classList.add("wrong", "tried");
          say(d, false, b.dataset.why ? "Not quite: " + b.dataset.why : "Not quite. Try again.");
        }
      };
    });
  }

  // multi pick: .drill.pick > .q, label.item[data-correct] (checkbox injected), .why
  function pick(d) {
    $$(d, ".item").forEach((it) => { const c = document.createElement("input"); c.type = "checkbox"; it.prepend(c); });
    checkBtn(d, "Check", () => {
      const bad = [];
      $$(d, ".item").forEach((it) => {
        const want = it.dataset.correct === "true", got = it.querySelector("input").checked;
        it.classList.toggle("miss", want !== got); if (want !== got) bad.push(it);
      });
      if (!bad.length) { $$(d, ".item input").forEach((c) => (c.disabled = true)); say(d, true, "Correct."); solved(d); }
      else say(d, false, `${bad.length} item${bad.length > 1 ? "s are" : " is"} wrong (marked).` + why(bad));
    });
  }

  // order: .drill.order > .q, ol.steps > li (in correct order in markup; shuffled at load), .why
  function order(d) {
    const ol = d.querySelector("ol.steps"); const items = [...ol.children];
    items.forEach((li, i) => { li.dataset.pos = i; });
    const render = (arr) => { ol.innerHTML = ""; arr.forEach((li) => ol.appendChild(li)); };
    const decorate = (li) => {
      const ctl = document.createElement("span"); ctl.className = "mv";
      ctl.innerHTML = `<button type="button" aria-label="Move up">▲</button><button type="button" aria-label="Move down">▼</button>`;
      const [up, dn] = ctl.querySelectorAll("button");
      up.onclick = () => { if (li.previousElementSibling) ol.insertBefore(li, li.previousElementSibling); clear(); };
      dn.onclick = () => { if (li.nextElementSibling) ol.insertBefore(li.nextElementSibling, li); clear(); };
      li.prepend(ctl);
      li.draggable = true;
      li.addEventListener("dragstart", (e) => { li.classList.add("dragging"); e.dataTransfer.effectAllowed = "move"; e.dataTransfer.setData("text/plain", ""); });
      li.addEventListener("dragend", () => { li.classList.remove("dragging"); clear(); });
    };
    ol.addEventListener("dragover", (e) => {
      e.preventDefault(); const drag = ol.querySelector(".dragging"); if (!drag) return;
      const after = [...ol.children].filter((x) => x !== drag).find((x) => e.clientY < x.getBoundingClientRect().top + x.offsetHeight / 2);
      after ? ol.insertBefore(drag, after) : ol.appendChild(drag);
    });
    const clear = () => [...ol.children].forEach((li) => li.classList.remove("good", "bad"));
    items.forEach(decorate);
    const scramble = () => { let s; do { s = shuffle(items.slice()); } while (items.length > 1 && s.every((x, i) => x === items[i])); render(s); clear(); d.classList.remove("solved"); const f = d.querySelector(".fb"); if (f) f.remove(); const w = d.querySelector(":scope > .why"); if (w) w.hidden = true; };
    scramble();
    checkBtn(d, "Check order", () => {
      const bad = []; [...ol.children].forEach((li, i) => { const ok = +li.dataset.pos === i; li.classList.toggle("good", ok); li.classList.toggle("bad", !ok); if (!ok) bad.push(li); });
      if (!bad.length) { say(d, true, "Correct order."); solved(d); } else say(d, false, `${bad.length} step${bad.length > 1 ? "s are" : " is"} out of place.` + why(bad));
    });
    resetBtn(d, scramble);
  }

  // sort: .drill.sort > .q, .bins > .bin[data-bin] (with h4), .chips > .chip[data-bin], .why
  function sort(d) {
    const chips = $$(d, ".chip"), bins = $$(d, ".bin"), pool = d.querySelector(".chips");
    let held = null;
    const hold = (c) => {
      if (held) { held.classList.remove("held"); held.setAttribute("aria-pressed", "false"); }
      held = held === c ? null : c;
      if (held) { held.classList.add("held"); held.setAttribute("aria-pressed", "true"); }
    };
    chips.forEach((c) => {
      c.type = "button"; c.draggable = true; c.setAttribute("aria-pressed", "false");
      c.onclick = (e) => { e.stopPropagation(); hold(c); };
      c.addEventListener("dragstart", (e) => { held = c; c.classList.add("held"); e.dataTransfer.setData("text/plain", ""); });
    });
    const drop = (target) => { if (!held) return; target.appendChild(held); held.classList.remove("held", "good", "bad"); held.setAttribute("aria-pressed", "false"); held = null; };
    [...bins, pool].forEach((b) => {
      const h = b.querySelector("h4");
      b.tabIndex = 0;
      b.setAttribute("role", "button");
      b.setAttribute("aria-label", b === pool ? "Return the picked item to the unsorted items" : "Place the picked item in: " + (h ? h.textContent.trim() : b.dataset.bin));
      b.addEventListener("keydown", (e) => {
        if (e.target !== b || (e.key !== "Enter" && e.key !== " ")) return;
        e.preventDefault(); drop(b.querySelector(".slot") || b);
      });
      b.addEventListener("click", () => drop(b.querySelector(".slot") || b));
      b.addEventListener("dragover", (e) => e.preventDefault());
      b.addEventListener("drop", (e) => { e.preventDefault(); drop(b.querySelector(".slot") || b); });
    });
    bins.forEach((b) => { if (!b.querySelector(".slot")) { const s = document.createElement("div"); s.className = "slot"; b.appendChild(s); } });
    shuffle(chips.slice()).forEach((c) => pool.appendChild(c));
    checkBtn(d, "Check", () => {
      const bad = []; let left = 0;
      chips.forEach((c) => { const bin = c.closest(".bin"); if (!bin) { left++; return; } const ok = bin.dataset.bin === c.dataset.bin; c.classList.toggle("good", ok); c.classList.toggle("bad", !ok); if (!ok) bad.push(c); });
      if (left) say(d, false, `${left} item${left > 1 ? "s" : ""} still to place.`);
      else if (bad.length) say(d, false, `${bad.length} in the wrong group (marked).` + why(bad));
      else { say(d, true, "All placed correctly."); solved(d); chips.forEach((c) => (c.disabled = true)); }
    });
  }

  // walk: .drill.walk > .drill (any type); later steps unlock when the previous is solved
  function walk(d) {
    const steps = $$(d, ":scope > .drill");
    steps.forEach((s, i) => { if (i) s.hidden = true; s.addEventListener("solved", () => { if (steps[i + 1]) { steps[i + 1].hidden = false; } else solved(d); }); });
  }

  function boot() {
    $$(document, ".drill .why").forEach((w) => (w.hidden = true));
    $$(document, ".drill.choice").forEach(choice);
    $$(document, ".drill.pick").forEach(pick);
    $$(document, ".drill.order").forEach(order);
    $$(document, ".drill.sort").forEach(sort);
    $$(document, ".drill.walk").forEach(walk);
    $$(document, ".answer").forEach((a) => { const d = a.previousElementSibling; if (d && d.matches(".drill")) d.addEventListener("solved", () => { a.hidden = false; }); });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot); else boot();
})();
