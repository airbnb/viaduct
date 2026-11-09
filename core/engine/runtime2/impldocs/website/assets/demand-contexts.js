/* Variant selectors: .demand-contexts > .context-control select chooses which .context-result[data-context] shows. */
(function () {
  "use strict";
  document.querySelectorAll(".demand-contexts").forEach((group) => {
    const control = group.querySelector(".context-control");
    const select = group.querySelector("select");
    if (!control || !select) return;
    const results = Array.from(group.querySelectorAll(".context-result"));
    const show = () => results.forEach((result) => {
      result.hidden = result.dataset.context !== select.value;
      result.open = true;
    });
    control.hidden = false;
    select.addEventListener("change", show);
    show();
  });
})();
