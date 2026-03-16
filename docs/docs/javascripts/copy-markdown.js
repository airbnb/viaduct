/**
 * Copy-as-Markdown dropdown for MkDocs Material theme.
 *
 * Injects a pill-shaped dropdown button in the top-right of the content area
 * offering 4 actions: Copy page, View as Markdown, Open in ChatGPT, Open in Claude.
 *
 * Supports:
 *  - Material's navigation.instant (via document$ observable)
 *  - MutationObserver fallback for other SPA setups
 *  - Older browsers via document.execCommand('copy')
 *  - Light/dark mode via CSS custom properties
 */

(function () {
  "use strict";

  // SVG icons -----------------------------------------------------------------

  var ICON_COPY =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">' +
    '<path d="M19 21H8V7h11m0-2H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2m-3-4H4a2 2 0 0 0-2 2v14h2V3h12V1z"/>' +
    "</svg>";

  var ICON_CHECK =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">' +
    '<path d="M21 7 9 19l-5.5-5.5 1.41-1.41L9 16.17 19.59 5.59 21 7z"/>' +
    "</svg>";

  var ICON_EYE =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">' +
    '<path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/>' +
    "</svg>";

  var ICON_CHATGPT =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">' +
    '<path d="M22.282 9.821a5.985 5.985 0 0 0-.516-4.91 6.046 6.046 0 0 0-6.51-2.9A6.065 6.065 0 0 0 4.981 4.18a5.985 5.985 0 0 0-3.998 2.9 6.046 6.046 0 0 0 .743 7.097 5.98 5.98 0 0 0 .51 4.911 6.051 6.051 0 0 0 6.515 2.9A5.985 5.985 0 0 0 13.26 24a6.056 6.056 0 0 0 5.772-4.206 5.99 5.99 0 0 0 3.997-2.9 6.056 6.056 0 0 0-.747-7.073zM13.26 22.43a4.476 4.476 0 0 1-2.876-1.04l.141-.081 4.779-2.758a.795.795 0 0 0 .392-.681v-6.737l2.02 1.168a.071.071 0 0 1 .038.052v5.583a4.504 4.504 0 0 1-4.494 4.494zM3.6 18.304a4.47 4.47 0 0 1-.535-3.014l.142.085 4.783 2.759a.771.771 0 0 0 .78 0l5.843-3.369v2.332a.08.08 0 0 1-.033.062L9.74 19.95a4.5 4.5 0 0 1-6.14-1.646zM2.34 7.896a4.485 4.485 0 0 1 2.366-1.973V11.6a.766.766 0 0 0 .388.676l5.815 3.355-2.02 1.168a.076.076 0 0 1-.071 0l-4.83-2.786A4.504 4.504 0 0 1 2.34 7.896zm16.597 3.855l-5.843-3.372L15.115 7.2a.076.076 0 0 1 .071 0l4.83 2.786a4.494 4.494 0 0 1-.676 8.105v-5.678a.79.79 0 0 0-.403-.662zm2.01-3.023l-.141-.085-4.774-2.782a.776.776 0 0 0-.785 0L9.409 9.23V6.897a.066.066 0 0 1 .028-.061l4.83-2.787a4.5 4.5 0 0 1 6.68 4.66zm-12.64 4.135l-2.02-1.164a.08.08 0 0 1-.038-.057V6.075a4.5 4.5 0 0 1 7.375-3.453l-.142.08L8.704 5.46a.795.795 0 0 0-.393.681zm1.097-2.365l2.602-1.5 2.607 1.5v2.999l-2.597 1.5-2.607-1.5z"/>' +
    "</svg>";

  // Official Claude AI symbol (https://upload.wikimedia.org/wikipedia/commons/b/b0/Claude_AI_symbol.svg)
  var ICON_CLAUDE =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 1200">' +
    '<path style="fill: #d97757" d="M 233.959793 800.214905 L 468.644287 668.536987 L 472.590637 657.100647 L 468.644287 650.738403 L 457.208069 650.738403 L 417.986633 648.322144 L 283.892639 644.69812 L 167.597321 639.865845 L 54.926208 633.825623 L 26.577238 627.785339 L 3.3e-05 592.751709 L 2.73832 575.27533 L 26.577238 559.248352 L 60.724873 562.228149 L 136.187973 567.382629 L 249.422867 575.194763 L 331.570496 580.026978 L 453.261841 592.671082 L 472.590637 592.671082 L 475.328857 584.859009 L 468.724915 580.026978 L 463.570557 575.194763 L 346.389313 495.785217 L 219.543671 411.865906 L 153.100723 363.543762 L 117.181267 339.060425 L 99.060455 316.107361 L 91.248367 266.01355 L 123.865784 230.093994 L 167.677887 233.073853 L 178.872513 236.053772 L 223.248367 270.201477 L 318.040283 343.570496 L 441.825592 434.738342 L 459.946411 449.798706 L 467.194672 444.64447 L 468.080597 441.020203 L 459.946411 427.409485 L 392.617493 305.718323 L 320.778564 181.932983 L 288.80542 130.630859 L 280.348999 99.865845 C 277.369171 87.221436 275.194641 76.590698 275.194641 63.624268 L 312.322174 13.20813 L 332.8591 6.604126 L 382.389313 13.20813 L 403.248352 31.328979 L 434.013519 101.71814 L 483.865753 212.537048 L 561.181274 363.221497 L 583.812134 407.919434 L 595.892639 449.315491 L 600.40271 461.959839 L 608.214783 461.959839 L 608.214783 454.711609 L 614.577271 369.825623 L 626.335632 265.61084 L 637.771851 131.516846 L 641.718201 93.745117 L 660.402832 48.483276 L 697.530334 24.000122 L 726.52356 37.852417 L 750.362549 72 L 747.060486 94.067139 L 732.886047 186.201416 L 705.100708 330.52356 L 686.979919 427.167847 L 697.530334 427.167847 L 709.61084 415.087341 L 758.496704 350.174561 L 840.644348 247.490051 L 876.885925 206.738342 L 919.167847 161.71814 L 946.308838 140.29541 L 997.61084 140.29541 L 1035.38269 196.429626 L 1018.469849 254.416199 L 965.637634 321.422852 L 921.825562 378.201538 L 859.006714 462.765259 L 819.785278 530.41626 L 823.409424 535.812073 L 832.75177 534.92627 L 974.657776 504.724915 L 1051.328979 490.872559 L 1142.818848 475.167786 L 1184.214844 494.496582 L 1188.724854 514.147644 L 1172.456421 554.335693 L 1074.604126 578.496765 L 959.838989 601.449829 L 788.939636 641.879272 L 786.845764 643.409485 L 789.261841 646.389343 L 866.255127 653.637634 L 899.194702 655.409424 L 979.812134 655.409424 L 1129.932861 666.604187 L 1169.154419 692.537109 L 1192.671265 724.268677 L 1188.724854 748.429688 L 1128.322144 779.194641 L 1046.818848 759.865845 L 856.590759 714.604126 L 791.355774 698.335754 L 782.335693 698.335754 L 782.335693 703.731567 L 836.69812 756.885986 L 936.322205 846.845581 L 1061.073975 962.81897 L 1067.436279 991.490112 L 1051.409424 1014.120911 L 1034.496704 1011.704712 L 924.885986 929.234924 L 882.604126 892.107544 L 786.845764 811.48999 L 780.483276 811.48999 L 780.483276 819.946289 L 802.550415 852.241699 L 919.087341 1027.409424 L 925.127625 1081.127686 L 916.671204 1098.604126 L 886.469849 1109.154419 L 853.288696 1103.114136 L 785.073914 1007.355835 L 714.684631 899.516785 L 657.906067 802.872498 L 650.979858 806.81897 L 617.476624 1167.704834 L 601.771851 1186.147705 L 565.530212 1200 L 535.328857 1177.046997 L 519.302124 1139.919556 L 535.328857 1066.550537 L 554.657776 970.792053 L 570.362488 894.68457 L 584.536926 800.134277 L 592.993347 768.724976 L 592.429626 766.630859 L 585.503479 767.516968 L 514.22821 865.369263 L 405.825531 1011.865906 L 320.053711 1103.677979 L 299.516815 1111.812256 L 263.919525 1093.369263 L 267.221497 1060.429688 L 287.114136 1031.114136 L 405.825531 880.107361 L 477.422913 786.52356 L 523.651062 732.483276 L 523.328918 724.671265 L 520.590698 724.671265 L 205.288605 929.395935 L 149.154434 936.644409 L 124.993355 914.01355 L 127.973183 876.885986 L 139.409409 864.80542 L 234.201385 799.570435 Z"/>' +
    "</svg>";

  var ICON_CHEVRON =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">' +
    '<path d="M7 10l5 5 5-5z"/>' +
    "</svg>";

  // Helpers -------------------------------------------------------------------

  function isHomepage() {
    var path = window.location.pathname;
    return path === "/" || path === "/index.html";
  }

  function getPageMdUrl() {
    return window.location.pathname.replace(/\/?$/, "/") + "page.md";
  }

  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text);
    }
    // Fallback for older browsers.
    return new Promise(function (resolve, reject) {
      var ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.focus();
      ta.select();
      try {
        var ok = document.execCommand("copy");
        document.body.removeChild(ta);
        if (ok) {
          resolve();
        } else {
          reject(new Error("execCommand copy failed"));
        }
      } catch (err) {
        document.body.removeChild(ta);
        reject(err);
      }
    });
  }

  // Actions -------------------------------------------------------------------

  function actionCopy(dropdown) {
    var pageMdUrl = getPageMdUrl();
    fetch(pageMdUrl)
      .then(function (res) {
        if (!res.ok) throw new Error("HTTP " + res.status);
        return res.text();
      })
      .then(function (text) {
        return copyText(text);
      })
      .then(function () {
        // Show success state for 2 seconds.
        var trigger = dropdown.querySelector(".copy-md-trigger");
        var label = dropdown.querySelector(".copy-md-label");
        var iconEl = dropdown.querySelector(".copy-md-trigger-icon");
        if (label) label.textContent = "Copied!";
        if (iconEl) iconEl.innerHTML = ICON_CHECK;
        if (trigger) trigger.classList.add("copy-md-trigger--success");
        setTimeout(function () {
          if (label) label.textContent = "Copy page";
          if (iconEl) iconEl.innerHTML = ICON_COPY;
          if (trigger) trigger.classList.remove("copy-md-trigger--success");
        }, 2000);
      })
      .catch(function (err) {
        console.error("[copy-markdown] Failed to copy:", err);
      });
  }

  function actionView() {
    window.open(getPageMdUrl(), "_blank", "noopener");
  }

  function actionChatGPT() {
    var prompt = "Explain this documentation page from Viaduct:\n\n" + window.location.href;
    window.open("https://chatgpt.com/?q=" + encodeURIComponent(prompt), "_blank", "noopener");
  }

  function actionClaude() {
    var prompt = "Explain this documentation page from Viaduct:\n\n" + window.location.href;
    window.open("https://claude.ai/new?q=" + encodeURIComponent(prompt), "_blank", "noopener");
  }

  // Dropdown injection --------------------------------------------------------

  function injectDropdown() {
    // Don't add dropdown on the homepage.
    if (isHomepage()) return;

    // Remove stale dropdown from previous instant navigation.
    var existing = document.getElementById("copy-md-dropdown");
    if (existing) existing.parentNode.removeChild(existing);

    var container =
      document.querySelector(".md-content__inner") ||
      document.querySelector("article");

    if (!container) return;

    // Build dropdown element.
    var dropdown = document.createElement("div");
    dropdown.className = "copy-md-dropdown";
    dropdown.id = "copy-md-dropdown";

    dropdown.innerHTML =
      '<button class="copy-md-trigger" aria-label="Copy page as Markdown" aria-haspopup="true" aria-expanded="false">' +
        '<span class="copy-md-trigger-icon">' + ICON_COPY + '</span>' +
        '<span class="copy-md-label">Copy page</span>' +
        '<span class="copy-md-chevron-wrap" aria-label="Open menu">' +
          '<span class="copy-md-chevron">' + ICON_CHEVRON + '</span>' +
        '</span>' +
      '</button>' +
      '<div class="copy-md-menu" role="menu">' +
        '<button class="copy-md-menu-item" data-action="copy" role="menuitem">' +
          '<span class="copy-md-menu-icon">' + ICON_COPY + '</span>' +
          'Copy page' +
        '</button>' +
        '<button class="copy-md-menu-item" data-action="view" role="menuitem">' +
          '<span class="copy-md-menu-icon">' + ICON_EYE + '</span>' +
          'View as Markdown' +
        '</button>' +
        '<a class="copy-md-menu-item" data-action="chatgpt" role="menuitem" target="_blank" rel="noopener">' +
          '<span class="copy-md-menu-icon">' + ICON_CHATGPT + '</span>' +
          'Open in ChatGPT' +
        '</a>' +
        '<a class="copy-md-menu-item" data-action="claude" role="menuitem" target="_blank" rel="noopener">' +
          '<span class="copy-md-menu-icon">' + ICON_CLAUDE + '</span>' +
          'Open in Claude' +
        '</a>' +
      '</div>';

    // Trigger: main area = copy action; chevron area = toggle menu.
    var trigger = dropdown.querySelector(".copy-md-trigger");
    var chevronWrap = dropdown.querySelector(".copy-md-chevron-wrap");
    var menu = dropdown.querySelector(".copy-md-menu");

    function openMenu() {
      dropdown.classList.add("copy-md-dropdown--open");
      trigger.setAttribute("aria-expanded", "true");
    }

    function closeMenu() {
      dropdown.classList.remove("copy-md-dropdown--open");
      trigger.setAttribute("aria-expanded", "false");
    }

    function isOpen() {
      return dropdown.classList.contains("copy-md-dropdown--open");
    }

    // Chevron click: toggle menu.
    chevronWrap.addEventListener("click", function (e) {
      e.stopPropagation();
      if (isOpen()) {
        closeMenu();
      } else {
        openMenu();
      }
    });

    // Main trigger click (not chevron): execute copy action directly.
    trigger.addEventListener("click", function (e) {
      // Only act if the chevron area wasn't clicked.
      if (chevronWrap.contains(e.target)) return;
      closeMenu();
      actionCopy(dropdown);
    });

    // Menu item clicks.
    menu.addEventListener("click", function (e) {
      var item = e.target.closest("[data-action]");
      if (!item) return;
      var action = item.getAttribute("data-action");
      closeMenu();
      if (action === "copy") actionCopy(dropdown);
      else if (action === "view") actionView();
      else if (action === "chatgpt") actionChatGPT();
      else if (action === "claude") actionClaude();
    });

    // Close on outside click.
    document.addEventListener("click", function (e) {
      if (!dropdown.contains(e.target)) {
        closeMenu();
      }
    });

    // Insert as the first child so it floats to the top-right.
    var firstChild = container.firstChild;
    if (firstChild) {
      container.insertBefore(dropdown, firstChild);
    } else {
      container.appendChild(dropdown);
    }
  }

  // Initialisation ------------------------------------------------------------

  function init() {
    injectDropdown();
  }

  // Material instant navigation support.
  if (typeof window.document$ !== "undefined") {
    window.document$.subscribe(function () {
      init();
    });
  } else {
    document.addEventListener("DOMContentLoaded", function () {
      if (typeof window.document$ !== "undefined") {
        window.document$.subscribe(function () {
          init();
        });
      } else {
        // Fallback: MutationObserver on the article element.
        init();
        var observer = new MutationObserver(function () {
          if (!document.getElementById("copy-md-dropdown")) {
            init();
          }
        });
        var target = document.querySelector(".md-content") || document.body;
        observer.observe(target, { childList: true, subtree: true });
      }
    });
  }
})();
