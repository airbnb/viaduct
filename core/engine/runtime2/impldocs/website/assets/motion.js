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
 * Playback is manual by default: Prev/Next animate one step (beat) at a time, Reset returns to the first step, and Auto plays the rest.
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
      on: windows(d.on),
      text: null,
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

  function apply(a, t) {
    const el = a.el;
    let op = 1;
    let p = 1;
    if (a.tin !== null) {
      p = a.dur > 0 ? clamp((t - a.tin) / a.dur) : (t >= a.tin ? 1 : 0);
      if (t < a.tin) op = 0;
    }
    const e = a.linear ? p : ease(p);
    if (a.tout !== null) op *= 1 - clamp((t - a.tout) / 0.4);
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
    if (a.on.length) el.classList.toggle("on", a.on.some(([x, y]) => t >= x && t < y));
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
      let s = a.text[0][1];
      for (const [k, v] of a.text) if (t >= k) s = v;
      if (a.textEl.textContent !== s) a.textEl.textContent = s;
    }
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
    const loop = fig.dataset.loop !== undefined;
    const items = [...svg.querySelectorAll("[data-in], [data-on], [data-text], [data-out], [data-add]")].map(prepare);

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
      `<div class="track"><input type="range" min="0" max="${D}" step="0.01" value="0" aria-label="Timeline">` +
      `<div class="ticks">${beats.map((b) => `<button class="tick" style="left:${(b.at / D) * 100}%" data-i="${b.i}" aria-label="Step ${b.i + 1}" title="${b.title.replace(/"/g, "&quot;")}"></button>`).join("")}</div></div>` +
      `<label class="auto" title="Play all steps automatically"><input type="checkbox"> Auto</label>` +
      `<button class="speed" title="Playback speed">1×</button>`;
    fig.querySelector(".stage").after(ctl);
    ctl.after(cap);
    const range = ctl.querySelector("input[type=range]");
    const resetBtn = ctl.querySelector(".reset");
    const prevBtn = ctl.querySelector(".prev");
    const nextBtn = ctl.querySelector(".next");
    const autoBox = ctl.querySelector(".auto input");
    const speedBtn = ctl.querySelector(".speed");
    const beatNo = ctl.querySelector(".beatno");
    const ticks = [...ctl.querySelectorAll(".tick")];

    let t = 0, playing = false, last = 0, speed = 1, curBeat = -1, raf = 0, timer = 0, stopAt = D;
    const endOf = (k) => (beats[k + 1] ? beats[k + 1].at - 0.01 : D);

    function beatAt(time) {
      let k = 0;
      beats.forEach((b, i) => { if (time >= b.at - 1e-6) k = i; });
      return k;
    }
    function disable(btn, off) {
      if (off && document.activeElement === btn) fig.focus({ preventScroll: true });
      btn.disabled = off;
    }
    function render() {
      items.forEach((a) => apply(a, t));
      range.value = t;
      range.style.setProperty("--p", (t / D) * 100 + "%");
      const k = beats.length ? beatAt(t) : -1;
      if (k !== curBeat && k >= 0) {
        curBeat = k;
        const b = beats[k];
        cap.innerHTML = `<span class="k">Step ${k + 1} of ${beats.length}</span>` + (b.title ? `<span class="t">${b.title}</span>` : "") + `<div class="d">${b.li.innerHTML}</div>`;
        cap.classList.remove("swap"); void cap.offsetWidth; cap.classList.add("swap");
        ticks.forEach((x) => x.classList.toggle("on", +x.dataset.i === b.i));
        ticks.forEach((x) => x.classList.toggle("past", +x.dataset.i < b.i));
        beatNo.textContent = `${k + 1} / ${beats.length}`;
        fig.dispatchEvent(new CustomEvent("beat", { detail: k }));
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
    function pause() { playing = false; cancelAnimationFrame(raf); clearTimeout(timer); render(); }
    function seek(x) { t = Math.max(0, Math.min(D, x)); render(); }
    function step(k) {
      k = Math.max(0, Math.min(beats.length - 1, k));
      if (!beats[k]) return;
      autoBox.checked = false;
      run(beats[k].at, endOf(k));
    }

    nextBtn.onclick = () => step(playing ? curBeat + 1 : (t < endOf(curBeat) - 0.02 ? curBeat : curBeat + 1));
    prevBtn.onclick = () => step(curBeat - 1);
    resetBtn.onclick = () => { autoBox.checked = false; pause(); seek(endOf(0)); };
    autoBox.onchange = () => { if (autoBox.checked) run(t >= D - 0.02 ? 0 : t, D); else pause(); };
    range.addEventListener("input", () => { autoBox.checked = false; pause(); seek(parseFloat(range.value)); });
    ticks.forEach((x) => (x.onclick = () => step(+x.dataset.i)));
    speedBtn.onclick = () => {
      speed = speed === 1 ? 2 : speed === 2 ? 0.5 : 1;
      speedBtn.textContent = (speed === 0.5 ? "½" : speed) + "×";
    };
    fig.goBeat = (i) => step(i);
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
