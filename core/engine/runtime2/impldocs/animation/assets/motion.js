/* From https://git.musta.ch/alex-ubillus/viaduct-runtime2 (commit 056e72078fe5778716b584f7f7d84a4b1cab9c65). */
/* Local adaptations: multiple SVGs, snapshot viewBoxes, task crossfades, slider targets, scroll focus, and resizable panes. */
/* Timeline-driven SVG motion graphics.
 *
 * <figure class="scene" data-duration="12" [data-loop]>
 *   <div class="stage"><svg viewBox="...">
 *     <g data-in="1" data-dur=".6" data-anim="fade|pop|slide|draw|move" data-from="-40,0"
 *        data-path="#pathId" data-out="9" data-on="3-5,7-8" data-text="0:pending|4:Alice">...</g>
 *   </svg></div>
 *   <ol class="beats"><li data-at="0" data-title="...">caption</li>...</ol>
 * </figure>
 *
 * data-add="5:full,12:-full" adds class "full" from t=5 and removes it from t=12. data-ease="linear" for constant speed.
 * Playback is manual by default: Next animates one step (beat), Prev and timeline marks jump to a completed step, Reset returns to the first step, and Auto plays the rest.
 * Each beat's HTML is shown as the step's explanation, so it may hold rich text (code, lists).
 * Rendering is a pure function of time, so scrubbing, seeking and reduced motion all work.
 * Animated elements must not carry their own transform attribute (CSS transform would replace it):
 * wrap them in a <g>. "move" elements are drawn at the origin and translated along data-path.
 */
(function () {
  "use strict";
  const params = new URLSearchParams(location.search);
  const STATIC = params.has("static");
  const FORCE_T = params.has("t") ? parseFloat(params.get("t")) : null;
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const ease = (k) => (k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2);
  const clamp = (x) => Math.max(0, Math.min(1, x));

  function windows(spec) {
    return (spec || "").split(",").map((w) => w.trim()).filter(Boolean).map((w) => {
      const [a, b] = w.split("-").map(parseFloat);
      return [a, isNaN(b) ? Infinity : b];
    });
  }

  function prepare(el) {
    const d = el.dataset;
    const a = {
      el,
      anim: d.anim || "fade",
      tin: d.in !== undefined ? parseFloat(d.in) : null,
      dur: d.dur !== undefined ? parseFloat(d.dur) : 0.6,
      tout: d.out !== undefined ? parseFloat(d.out) : null,
      outDur: parseFloat(d.outDur || "0.4"),
      on: windows(d.on),
      text: null,
      textFade: parseFloat(d.textFade || "0"),
      textInstant: (d.textInstant || "").split(",").filter(Boolean).map(Number),
      from: (d.from || "0,24").split(",").map(parseFloat),
      add: (d.add || "").split(",").filter(Boolean).map((p) => { const i = p.indexOf(":"); return [parseFloat(p.slice(0, i)), p.slice(i + 1)]; }).sort((x, y) => x[0] - y[0]),
      linear: d.ease === "linear",
    };
    if (d.text) {
      a.text = d.text.split("|").map((p) => {
        const i = p.indexOf(":");
        return [parseFloat(p.slice(0, i)), p.slice(i + 1)];
      }).sort((x, y) => x[0] - y[0]);
      a.textEl = el.matches("text, tspan") ? el : el.querySelector("text");
      if (a.textFade && a.textEl) {
        a.previousText = a.textEl.cloneNode(true);
        a.previousText.setAttribute("aria-hidden", "true");
        a.textEl.before(a.previousText);
      }
    }
    if (a.anim === "draw") {
      const shapes = el.matches("path, line, polyline, polygon, circle, rect, ellipse") ? [el] : [...el.querySelectorAll("path, line, polyline, polygon")];
      a.shapes = shapes.map((s) => {
        let len = 0;
        try { len = s.getTotalLength(); } catch (e) { len = 0; }
        const dashed = getComputedStyle(s).strokeDasharray !== "none";
        if (!dashed) s.style.strokeDasharray = `${len} ${len}`;
        s._marker = s.getAttribute("marker-end");
        return [s, len, dashed];
      });
    }
    if (a.anim === "move") {
      a.path = document.querySelector(d.path);
      try { a.len = a.path.getTotalLength(); } catch (e) { a.len = 0; }
    }
    if (a.anim === "pop" || a.anim === "slide") {
      el.style.transformBox = "fill-box";
      el.style.transformOrigin = "center";
    }
    return a;
  }

  function setText(el, value) {
    const finished = el.classList.contains("task-status") && value === "finished";
    if (el.textContent === (finished ? "✓ finished" : value)) return;
    if (!finished) { el.textContent = value; return; }
    const check = document.createElementNS("http://www.w3.org/2000/svg", "tspan");
    check.setAttribute("class", "task-check");
    check.setAttribute("aria-hidden", "true");
    check.textContent = "✓";
    el.replaceChildren(check, document.createTextNode(" finished"));
  }

  function apply(a, t) {
    const el = a.el;
    let op = 1;
    let p = 1;
    if (a.tin !== null) {
      p = a.dur > 0 ? clamp((t - a.tin) / a.dur) : (t >= a.tin ? 1 : 0);
      if (t < a.tin) op = 0;
    }
    const e = a.linear ? p : ease(p);
    if (a.tout !== null) op *= 1 - clamp((t - a.tout) / a.outDur);
    switch (a.anim) {
      case "fade": op *= e; break;
      case "pop": op *= Math.min(1, e * 1.6); el.style.transform = `scale(${0.55 + 0.45 * e})`; break;
      case "slide": op *= e; el.style.transform = `translate(${(1 - e) * a.from[0]}px, ${(1 - e) * a.from[1]}px)`; break;
      case "draw":
        a.shapes.forEach(([s, len, dashed]) => {
          if (dashed) s.style.opacity = e;
          else s.style.strokeDashoffset = `${len * (1 - e)}`;
          if (s._marker) {
            const want = e >= 0.97 ? s._marker : "none";
            if (s.getAttribute("marker-end") !== want) s.setAttribute("marker-end", want);
          }
        });
        if (a.tin !== null && t < a.tin) op = 0;
        break;
      case "move":
        if (a.path && a.len) {
          const pt = a.path.getPointAtLength(e * a.len);
          el.style.transform = `translate(${pt.x}px, ${pt.y}px)`;
        }
        if (a.tin !== null && t < a.tin) op = 0;
        else if (a.tin !== null && el.dataset.keep === undefined) op *= 1 - clamp((t - a.tin - a.dur) / 0.25);
        break;
    }
    el.style.opacity = op;
    el.style.visibility = op <= 0.001 ? "hidden" : "";
    if (a.on.length) {
      const active = a.on.some(([x, y]) => t >= x && t < y);
      el.classList.toggle("on", active);
      if (active && el.dataset.viewbox) el.ownerSVGElement.setAttribute("viewBox", el.dataset.viewbox);
    }
    if (a.add.length) {
      const st = {};
      a.add.forEach(([k, c]) => {
        const neg = c.startsWith("-"), name = neg ? c.slice(1) : c;
        if (!(name in st)) st[name] = false;
        if (t >= k) st[name] = !neg;
      });
      for (const name in st) el.classList.toggle(name, st[name]);
    }
    if (a.text && a.textEl) {
      let index = 0;
      a.text.forEach(([at], i) => { if (t >= at) index = i; });
      const [at, value] = a.text[index];
      const fade = reduced || a.textInstant.includes(at) ? 0 : a.textFade;
      a.currentText = { at, value, fade };
      setText(a.textEl, value);
      if (a.previousText) {
        const opacity = index && fade ? ease(clamp((t - at) / fade)) : 1;
        setText(a.previousText, index ? a.text[index - 1][1] : "");
        a.previousText.style.opacity = Math.max(0, 1 - 2 * opacity);
        a.textEl.style.opacity = Math.max(0, 2 * opacity - 1);
      }
    }
  }

  function focusChanges(pane, time, instant) {
    const snapshot = pane.querySelector(".snapshot.on, .demand-snapshot.on");
    if (!snapshot) return;
    if (snapshot.classList.contains("demand-snapshot")) {
      const targets = [...snapshot.querySelectorAll("[data-focus]")];
      if (!targets.length) return;
      const boxes = targets.map((el) => el.getBoundingClientRect());
      const viewport = pane.getBoundingClientRect();
      const center = (Math.min(...boxes.map((b) => b.top)) + Math.max(...boxes.map((b) => b.bottom))) / 2;
      pane.scrollTo({ top: pane.scrollTop + center - viewport.top - pane.clientTop - pane.clientHeight / 2, behavior: reduced || instant ? "instant" : "smooth" });
      return;
    }
    const phaseAt = Math.max(0, ...(snapshot.dataset.focusAt || "").split(",").map(Number).filter((at) => at <= time));
    let targets = [...snapshot.querySelectorAll(".cell[data-focus]")].filter((el) => {
      const at = Number(el.parentElement.dataset.in || 0);
      return at <= time && at >= phaseAt;
    });
    if (!targets.length) targets = [...snapshot.querySelectorAll(".box[data-focus]")];
    if (!targets.length) return;
    const boxes = targets.map((el) => el.getBBox());
    const x = (Math.min(...boxes.map((b) => b.x)) + Math.max(...boxes.map((b) => b.x + b.width))) / 2;
    const y = (Math.min(...boxes.map((b) => b.y)) + Math.max(...boxes.map((b) => b.y + b.height))) / 2;
    const svg = snapshot.ownerSVGElement;
    const bounds = svg.getBoundingClientRect();
    const viewport = pane.getBoundingClientRect();
    const viewBox = svg.viewBox.baseVal;
    const scale = bounds.width / viewBox.width;
    pane.scrollTo({
      left: pane.scrollLeft + bounds.left - viewport.left - pane.clientLeft + (x - viewBox.x) * scale - pane.clientWidth / 2,
      top: pane.scrollTop + bounds.top - viewport.top - pane.clientTop + (y - viewBox.y) * scale - pane.clientHeight / 2,
      behavior: reduced || instant ? "instant" : "smooth",
    });
  }

  function initPaneResizer(fig, pane, cap) {
    const grip = document.createElement("div");
    grip.className = "pane-resizer";
    grip.tabIndex = 0;
    grip.setAttribute("role", "separator");
    grip.setAttribute("aria-orientation", "horizontal");
    grip.setAttribute("aria-label", "Resize resolution and step description panes");
    grip.title = "Drag or use Up/Down arrow keys to resize both panes";
    const min = 160, max = 1000;
    grip.setAttribute("aria-valuemin", min);
    grip.setAttribute("aria-valuemax", max);
    cap.after(grip);
    const height = () => pane.getBoundingClientRect().height;
    const setHeight = (value) => fig.style.setProperty("--pane-height", Math.max(min, Math.min(max, value)) + "px");
    new ResizeObserver(() => {
      grip.setAttribute("aria-valuenow", Math.round(height()));
      grip.setAttribute("aria-valuetext", Math.round(height()) + " pixels per pane");
    }).observe(pane);
    let drag = null;
    grip.addEventListener("pointerdown", (e) => {
      if (!e.isPrimary || e.button !== 0) return;
      e.preventDefault();
      grip.focus({ preventScroll: true });
      const rows = cap.getBoundingClientRect().top >= pane.getBoundingClientRect().bottom - 1 ? 2 : 1;
      drag = { id: e.pointerId, y: e.clientY, height: height(), rows };
      grip.setPointerCapture(e.pointerId);
    });
    grip.addEventListener("pointermove", (e) => {
      if (drag && e.pointerId === drag.id) setHeight(drag.height + (e.clientY - drag.y) / drag.rows);
    });
    const stop = () => { drag = null; };
    grip.addEventListener("pointerup", stop);
    grip.addEventListener("pointercancel", stop);
    grip.addEventListener("lostpointercapture", stop);
    grip.addEventListener("keydown", (e) => {
      if (e.altKey || e.ctrlKey || e.metaKey) return;
      const value = { ArrowUp: height() - 20, ArrowDown: height() + 20, Home: min, End: max }[e.key];
      if (value === undefined) return;
      e.preventDefault();
      e.stopPropagation();
      setHeight(value);
    });
  }

  function initCodePointers(fig, pause) {
    const dialog = document.querySelector(".source-dialog");
    if (!dialog) return;
    const references = JSON.parse(document.getElementById("code-references").textContent);
    const pane = dialog.querySelector(".source-scroll");
    const code = dialog.querySelector(".source-code");
    const selection = dialog.querySelector(".source-selection");
    let opener = null, reference = null, anchor = null;

    function highlight(ranges, focus = false) {
      code.querySelectorAll(".source-line").forEach((line) => {
        const number = Number(line.dataset.line);
        line.classList.toggle("source-highlight", ranges.some(([first, last]) => number >= first && number <= last));
      });
      selection.textContent = "Highlighted lines: " + ranges.map(([first, last]) => first === last ? first : `${first}–${last}`).join(", ");
      if (focus) {
        const first = code.querySelector(".source-highlight");
        pane.scrollTop = first ? Math.max(0, pane.scrollTop + first.getBoundingClientRect().top - pane.getBoundingClientRect().top - pane.clientHeight / 3) : 0;
        pane.scrollLeft = 0;
      }
    }

    fig.addEventListener("click", (e) => {
      const button = e.target.closest("[data-code-pointer]");
      if (!button) return;
      pause();
      opener = button;
      reference = references[button.dataset.codePointer];
      anchor = null;
      dialog.querySelector("#source-title").textContent = reference.title;
      dialog.querySelector(".source-path").textContent = reference.path;
      code.replaceChildren(document.getElementById(reference.file).content.cloneNode(true));
      dialog.showModal();
      highlight(reference.ranges, true);
      pane.focus({ preventScroll: true });
    });
    code.addEventListener("click", (e) => {
      const number = e.target.closest(".source-line-number");
      if (!number) return;
      const line = Number(number.parentElement.dataset.line);
      if (!e.shiftKey || anchor === null) anchor = line;
      highlight([[Math.min(anchor, line), Math.max(anchor, line)]]);
      pane.focus({ preventScroll: true });
    });
    dialog.querySelector(".source-reset").onclick = () => { anchor = null; highlight(reference.ranges, true); };
    dialog.querySelector(".source-close").onclick = () => dialog.close();
    dialog.addEventListener("click", (e) => {
      if (e.target !== dialog) return;
      const box = dialog.getBoundingClientRect();
      if (e.clientX < box.left || e.clientX > box.right || e.clientY < box.top || e.clientY > box.bottom) dialog.close();
    });
    dialog.addEventListener("close", () => opener?.focus({ preventScroll: true }));
  }

  function initScene(fig) {
    const svg = fig.querySelector(".stage svg");
    if (!svg) return;
    const beats = [...fig.querySelectorAll(":scope > ol.beats > li")].map((li, i) => ({
      li, i, at: parseFloat(li.dataset.at || "0"), title: li.dataset.title || "",
    })).sort((x, y) => x.at - y.at);
    beats.forEach((b, k) => (b.i = k));
    const lastAt = beats.length ? beats[beats.length - 1].at : 0;
    const D = parseFloat(fig.dataset.duration || String(lastAt + 3));
    const endOf = (k) => (beats[k + 1] ? beats[k + 1].at - 0.01 : D);
    const timelineStart = beats.length ? endOf(0) : 0;
    const progress = (time) => clamp((time - timelineStart) / (D - timelineStart || 1));
    const loop = fig.dataset.loop !== undefined;
    const items = [...fig.querySelector(".stage").querySelectorAll("[data-in], [data-on], [data-text], [data-out], [data-add]")].map(prepare);
    const taskFocus = (svg.dataset.taskFocus || "").split("|").filter(Boolean).map((point) => point.split(":").map(Number));
    const tasks = [...svg.querySelectorAll("[data-task-id]")].map((el) => ({ el, id: Number(el.dataset.taskId), highlight: el.querySelector(".task-highlight"), passive: el.querySelector(".task-passive"), status: items.find((a) => a.text && el.contains(a.el)) }));
    const taskPane = svg.closest(".task-track");

    const cap = document.createElement("div");
    cap.className = "caption";
    cap.setAttribute("aria-live", "polite");
    const ctl = document.createElement("div");
    ctl.className = "scene-ctl";
    ctl.innerHTML =
      `<button class="reset" aria-label="Back to the first step" title="Back to the first step">↺</button>` +
      `<button class="prev" aria-label="Previous step">‹ Prev</button>` +
      `<span class="beatno"></span>` +
      `<button class="next" aria-label="Next step">Next ›</button>` +
      `<div class="track"><input type="range" min="${timelineStart}" max="${D}" step="0.01" value="${timelineStart}" aria-label="Timeline">` +
      `<div class="ticks">${beats.map((b) => `<button class="tick" style="left:${progress(endOf(b.i)) * 100}%" data-i="${b.i}" aria-label="Step ${b.i + 1}" title="Step ${b.i + 1}: ${b.title.replace(/"/g, "&quot;")}">${(b.i + 1) % 5 === 0 ? `<span class="tick-label" aria-hidden="true">${b.i + 1}</span>` : ""}</button>`).join("")}</div></div>` +
      `<label class="auto" title="Play all steps automatically"><input type="checkbox"> Auto</label>` +
      `<button class="speed" title="Playback speed">1×</button>`;
    const oerPane = fig.querySelector(".oer-tree");
    const leftPane = fig.querySelector(".resolution-view") || oerPane;
    let activePane = oerPane;
    if (leftPane) {
      leftPane.before(ctl);
      leftPane.after(cap);
      initPaneResizer(fig, leftPane, cap);
    } else {
      fig.querySelector(".stage").after(ctl);
      ctl.after(cap);
    }
    const range = ctl.querySelector("input[type=range]");
    const resetBtn = ctl.querySelector(".reset");
    const prevBtn = ctl.querySelector(".prev");
    const nextBtn = ctl.querySelector(".next");
    const autoBox = ctl.querySelector(".auto input");
    const speedBtn = ctl.querySelector(".speed");
    const beatNo = ctl.querySelector(".beatno");
    const ticks = [...ctl.querySelectorAll(".tick")];

    let t = 0, playing = false, last = 0, speed = 1, curBeat = -1, raf = 0, timer = 0, stopAt = D;
    let focusPhase = "";
    let focusedTask = null;

    function beatAt(time) {
      let k = 0;
      beats.forEach((b, i) => { if (time >= b.at - 1e-6) k = i; });
      return k;
    }
    function disable(btn, off) {
      if (off && document.activeElement === btn) fig.focus({ preventScroll: true });
      btn.disabled = off;
    }
    function render(instant = false) {
      if (instant) cap.classList.remove("swap");
      items.forEach((a) => apply(a, t));
      if (taskFocus.length) {
        let index = 0;
        taskFocus.forEach(([at], i) => { if (t >= at) index = i; });
        const [at, from] = taskFocus[index];
        const [until, to] = taskFocus[index + 1] || taskFocus[index];
        const blend = until > at ? ease(clamp((t - at) / (until - at))) : 0;
        const active = blend < 0.5 ? from : to;
        tasks.forEach(({ el, id, highlight, passive, status }) => {
          const state = status?.currentText;
          const remaining = state?.value === "finished" ? (state.fade ? clamp((state.at + state.fade - t) / state.fade) : 0) : 1;
          const strength = reduced ? Number(id === active) : Number(id === from) * (1 - blend) + Number(id === to) * blend;
          highlight.style.opacity = Math.min(strength, remaining) * (1 - Number(passive?.style.opacity || 0));
          el.classList.toggle("on", id === active && remaining > 0);
        });
        if (taskPane && (active !== focusedTask || instant)) {
          const task = tasks.find(({ id, el }) => (!active || id === active) && el.style.visibility !== "hidden");
          if (task) {
            const card = task.el.querySelector(".task-card").getBoundingClientRect();
            const viewport = taskPane.getBoundingClientRect();
            taskPane.scrollTo({
              left: taskPane.scrollLeft + (card.left + card.right) / 2 - viewport.left - taskPane.clientLeft - taskPane.clientWidth / 2,
              behavior: reduced || instant ? "instant" : "smooth",
            });
            focusedTask = active;
          }
        }
      }
      range.value = t;
      range.style.setProperty("--p", progress(t) * 100 + "%");
      let arrived = 0;
      beats.forEach((b) => { if (t >= endOf(b.i) - 1e-6) arrived = b.i; });
      ticks.forEach((x) => {
        x.classList.toggle("on", +x.dataset.i === arrived);
        x.classList.toggle("past", +x.dataset.i < arrived);
      });
      const k = beats.length ? beatAt(t) : -1;
      if (k !== curBeat && k >= 0) {
        curBeat = k;
        const b = beats[k];
        cap.innerHTML = `<span class="k">Step ${k + 1} of ${beats.length}</span>` + (b.title ? `<span class="t">${b.title}</span>` : "") + `<div class="d">${b.li.innerHTML}</div>`;
        cap.scrollTop = 0;
        if (!instant) { cap.classList.remove("swap"); void cap.offsetWidth; cap.classList.add("swap"); }
        beatNo.textContent = `${k + 1} / ${beats.length}`;
        fig.dispatchEvent(new CustomEvent("beat", { detail: k }));
      }
      if (activePane) {
        const snapshot = activePane.querySelector(".snapshot.on, .demand-snapshot.on");
        const phase = `${activePane.id}:${k}:${snapshot?.dataset.in}:${(snapshot?.dataset.focusAt || "").split(",").filter((at) => at && t >= Number(at)).length}`;
        if (phase !== focusPhase || instant) {
          focusPhase = phase;
          focusChanges(activePane, t, instant);
        }
      }
      disable(prevBtn, curBeat <= 0 && !playing);
      disable(resetBtn, curBeat <= 0 && !playing);
      disable(nextBtn, curBeat >= beats.length - 1 && t >= D - 0.02);
      fig.classList.toggle("playing", playing);
    }
    function frame(now) {
      if (!playing) return;
      const dt = Math.min(0.1, (now - last) / 1000);
      last = now;
      t += dt * speed;
      if (t >= stopAt) {
        if (stopAt >= D && loop && autoBox.checked) t = 0;
        else { t = stopAt; playing = false; if (stopAt >= D) autoBox.checked = false; }
      }
      render();
      if (playing) raf = requestAnimationFrame(frame);
    }
    const beatMs = (k) => Math.max(2000, (endOf(k) - beats[k].at) * 1000) / speed;
    function autoNext() {
      const k = beatAt(t);
      if (k >= beats.length - 1) {
        if (!loop) { playing = false; autoBox.checked = false; render(); return; }
        t = endOf(0);
      } else t = endOf(k + 1);
      render();
      timer = setTimeout(autoNext, beatMs(beatAt(t)));
    }
    function run(from, to) {
      cancelAnimationFrame(raf); clearTimeout(timer);
      t = Math.max(0, Math.min(D, from)); stopAt = Math.min(D, to);
      if (reduced && autoBox.checked && beats.length) {
        // reduced motion: no tweening, one beat per interval
        playing = true;
        const k = beatAt(t);
        if (t < endOf(k) - 0.02) { t = endOf(k); render(); timer = setTimeout(autoNext, beatMs(k)); }
        else autoNext();
        return;
      }
      if (reduced) { t = stopAt; playing = false; autoBox.checked = false; render(); return; }
      playing = true; last = performance.now(); render();
      raf = requestAnimationFrame(frame);
    }
    function pause() { playing = false; cancelAnimationFrame(raf); clearTimeout(timer); render(true); }
    function seek(x, instant = true) { t = Math.max(0, Math.min(D, x)); render(instant); }
    function step(k) {
      k = Math.max(0, Math.min(beats.length - 1, k));
      if (!beats[k]) return;
      autoBox.checked = false;
      run(beats[k].at, endOf(k));
    }
    function jump(k) {
      k = Math.max(0, Math.min(beats.length - 1, k));
      if (!beats[k]) return;
      autoBox.checked = false;
      pause();
      seek(endOf(k));
    }

    nextBtn.onclick = () => step(playing ? curBeat + 1 : (t < endOf(curBeat) - 0.02 ? curBeat : curBeat + 1));
    prevBtn.onclick = () => jump(curBeat - 1);
    resetBtn.onclick = () => jump(0);
    autoBox.onchange = () => { if (autoBox.checked) run(t >= D - 0.02 ? 0 : t, D); else pause(); };
    range.addEventListener("input", () => { const target = parseFloat(range.value); autoBox.checked = false; pause(); seek(target); });
    ticks.forEach((x) => (x.onclick = () => jump(+x.dataset.i)));
    speedBtn.onclick = () => {
      speed = speed === 1 ? 2 : speed === 2 ? 0.5 : 1;
      speedBtn.textContent = (speed === 0.5 ? "½" : speed) + "×";
    };
    const viewTabs = [...fig.querySelectorAll('.view-tabs [role="tab"]:not(:disabled)')];
    viewTabs.forEach((tab, index) => {
      tab.onclick = () => {
        viewTabs.forEach((other) => {
          const selected = other === tab;
          other.setAttribute("aria-selected", String(selected));
          other.tabIndex = selected ? 0 : -1;
          fig.querySelector("#" + other.getAttribute("aria-controls")).hidden = !selected;
        });
        activePane = fig.querySelector("#" + tab.getAttribute("aria-controls"));
        focusPhase = "";
        render(true);
      };
      tab.addEventListener("keydown", (e) => {
        const next = { ArrowLeft: (index + viewTabs.length - 1) % viewTabs.length, ArrowRight: (index + 1) % viewTabs.length, Home: 0, End: viewTabs.length - 1 }[e.key];
        if (next === undefined || e.altKey || e.ctrlKey || e.metaKey) return;
        e.preventDefault();
        e.stopPropagation();
        viewTabs[next].click();
        viewTabs[next].focus({ preventScroll: true });
      });
    });
    fig.goBeat = (i) => jump(i);
    initCodePointers(fig, () => { autoBox.checked = false; pause(); });
    fig.tabIndex = 0;
    fig.addEventListener("keydown", (e) => {
      if (e.target.closest("input") || e.altKey || e.ctrlKey || e.metaKey) return;
      if (e.key === "ArrowRight") { e.preventDefault(); nextBtn.click(); }
      if (e.key === "ArrowLeft") { e.preventDefault(); prevBtn.click(); }
    });

    fig._scene = { beats: beats.map((b) => b.at), duration: D, seek: (x) => { pause(); seek(x); }, endOf };
    if (STATIC || FORCE_T !== null) {
      seek(FORCE_T !== null ? Math.min(D, FORCE_T) : D);
      return;
    }
    if (!beats.length) { seek(D); return; }
    seek(endOf(0));
  }

  function boot() {
    if (STATIC || FORCE_T !== null) document.documentElement.classList.add("static-caps");
    document.querySelectorAll("figure.scene").forEach(initScene);
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
