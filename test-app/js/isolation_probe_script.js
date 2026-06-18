(function() {
  "use strict";

  window.__userscriptVar = "USERSCRIPT";
  document.documentElement.setAttribute("data-userscript-var", "set");
  document.documentElement.setAttribute(
    "data-userscript-sees-page",
    String(typeof window.__pageVar));
})();
