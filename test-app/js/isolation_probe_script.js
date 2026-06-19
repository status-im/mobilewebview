(function () {
  "use strict";

  // User script is injected in page world and shares globals with the page.
  window.__userscriptVar = "USERSCRIPT";
  document.documentElement.setAttribute("data-userscript-var", "set");
})();
