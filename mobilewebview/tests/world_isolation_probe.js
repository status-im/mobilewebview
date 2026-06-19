/* eslint-disable no-undef */
// Runs in PAGE world (injected as a user script). It seeds page-world globals
// and a DOM marker, then reacts to the isolated-world probe via the shared DOM:
// when data-probe-nonce changes it records what the page world can observe.
(function () {
  "use strict";

  var root = document.documentElement;

  window.__pageVar = "PAGE";
  window.__userscriptVar = "USERSCRIPT";
  root.setAttribute("data-userscript-var", "set");

  function respond(nonce) {
    root.setAttribute("data-page-sees-bridge", typeof window.__bridgeVar);
    root.setAttribute("data-page-sees-userscript", typeof window.__userscriptVar);
    root.setAttribute("data-page-dom-marker", root.getAttribute("data-bridge-marker") || "null");
    root.setAttribute("data-page-nonce", nonce || "");
  }

  var observer = new MutationObserver(function () {
    var nonce = root.getAttribute("data-probe-nonce");
    if (nonce && nonce !== root.getAttribute("data-page-nonce")) {
      respond(nonce);
    }
  });
  observer.observe(root, { attributes: true, attributeFilter: ["data-probe-nonce"] });

  var preNonce = root.getAttribute("data-probe-nonce");
  if (preNonce) {
    respond(preNonce);
  }
})();
