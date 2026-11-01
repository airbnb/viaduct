/* From https://git.musta.ch/alex-ubillus/viaduct-runtime2 (commit 056e72078fe5778716b584f7f7d84a4b1cab9c65). */
(function () {
  "use strict";
  const root = document.documentElement;
  root.classList.add("js");
  let t = null;
  try { t = new URLSearchParams(location.search).get("theme"); } catch (e) { /* no URLSearchParams */ }
  if (!t) {
    try { const v = localStorage.getItem("r2v2:theme"); t = v === null ? null : JSON.parse(v); } catch (e) { t = null; }
  }
  if (t === "dark" || t === "light") root.dataset.theme = t;
})();
