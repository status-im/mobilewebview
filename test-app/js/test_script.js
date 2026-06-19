(function() {
  "use strict";

  var NAMESPACE = "qt";
  var OBJECT_NAME = "testBridge";
  var OVERLAY_ID = "__test_webchannel_overlay";
  var connectedObject = null;
  var hideTimer = null;
  var channelConnecting = false;
  var connectRetryTimer = null;

  function ensureOverlay() {
    var overlay = document.getElementById(OVERLAY_ID);
    if (!overlay) {
      overlay = document.createElement("div");
      overlay.id = OVERLAY_ID;
      overlay.style.position = "fixed";
      overlay.style.top = "12px";
      overlay.style.right = "12px";
      overlay.style.maxWidth = "70vw";
      overlay.style.padding = "10px 12px";
      overlay.style.background = "rgba(10,10,10,0.9)";
      overlay.style.color = "#fff";
      overlay.style.fontFamily = "sans-serif";
      overlay.style.fontSize = "14px";
      overlay.style.borderRadius = "8px";
      overlay.style.zIndex = "2147483647";
      overlay.style.pointerEvents = "none";
      overlay.style.display = "none";
      document.documentElement.appendChild(overlay);
    }
    return overlay;
  }

  function showOverlay(text, persistent) {
    var overlay = ensureOverlay();
    overlay.textContent = text;
    overlay.style.display = "block";

    if (hideTimer) {
      clearTimeout(hideTimer);
      hideTimer = null;
    }

    if (!persistent) {
      hideTimer = setTimeout(function() {
        overlay.style.display = "none";
      }, 1300);
    }
  }

  function updateOverlayFromBridge(prefix) {
    if (!connectedObject) {
      showOverlay("WebChannel object is not ready");
      return;
    }

    showOverlay(prefix + " | clickCount=" + connectedObject.clickCount + " | lastMessage=" + connectedObject.lastMessage);
  }

  function addTestButton() {
    if (document.getElementById("__test_webchannel_button")) {
      return;
    }

    var button = document.createElement("button");
    button.id = "__test_webchannel_button";
    button.textContent = "JS -> QML increment";
    button.style.position = "fixed";
    button.style.left = "12px";
    button.style.top = "12px";
    button.style.zIndex = "2147483647";
    button.style.padding = "8px 10px";
    button.style.border = "1px solid #222";
    button.style.background = "#ffffff";
    button.style.color = "#111";
    button.style.borderRadius = "6px";
    button.style.fontFamily = "sans-serif";

    button.addEventListener("click", function() {
      if (!connectedObject) {
        showOverlay("WebChannel not connected");
        ensureWebChannelConnected();
        return;
      }
      if (typeof connectedObject.incrementFromJs !== "function") {
        showOverlay("incrementFromJs() is unavailable");
        return;
      }
      connectedObject.incrementFromJs("button-click", function(newCount) {
        showOverlay("JS -> QML ok, newCount=" + newCount);
      });
    });

    document.documentElement.appendChild(button);
  }

  function stopConnectRetry() {
    if (connectRetryTimer) {
      clearInterval(connectRetryTimer);
      connectRetryTimer = null;
    }
  }

  function connectWebChannel() {
    if (connectedObject || channelConnecting) {
      return;
    }

    if (typeof QWebChannel !== "function") {
      return;
    }

    var transport = window[NAMESPACE] && window[NAMESPACE].webChannelTransport;
    if (!transport) {
      return;
    }

    channelConnecting = true;
    new QWebChannel(transport, function(channel) {
      channelConnecting = false;
      connectedObject = channel.objects[OBJECT_NAME];
      if (!connectedObject) {
        console.error("[test_script] WebChannel object '" + OBJECT_NAME + "' not found");
        return;
      }

      stopConnectRetry();

      if (connectedObject.clickCountChanged) {
        connectedObject.clickCountChanged.connect(function() {
          updateOverlayFromBridge("property changed");
        });
      }

      if (connectedObject.qmlEvent) {
        connectedObject.qmlEvent.connect(function(message) {
          showOverlay("signal from QML: " + message + " | count=" + connectedObject.clickCount);
        });
      }

      addTestButton();
      updateOverlayFromBridge("WebChannel connected");
    });
  }

  function ensureWebChannelConnected() {
    connectWebChannel();
    if (connectedObject || connectRetryTimer) {
      return;
    }

    var attempts = 0;
    connectRetryTimer = setInterval(function() {
      connectWebChannel();
      if (connectedObject || ++attempts >= 50) {
        stopConnectRetry();
      }
    }, 100);
  }

  window.__testWebChannel = {
    showPopupFromQml: function(text) {
      updateOverlayFromBridge("QML -> JS: " + text);
    },
    showStaticPopup: function() {
      if (!connectedObject) {
        showOverlay("WebChannel object is not ready", true);
        return;
      }
      showOverlay("counter=" + connectedObject.clickCount, true);
    },
    incrementViaWebChannel: function(reason) {
      if (!connectedObject) {
        showOverlay("WebChannel not connected");
        ensureWebChannelConnected();
        return;
      }
      if (typeof connectedObject.incrementFromJs !== "function") {
        showOverlay("incrementFromJs() is unavailable");
        return;
      }
      connectedObject.incrementFromJs(reason || "qml-trigger", function(newCount) {
        showOverlay("QML -> JS -> QML increment, count=" + newCount);
      });
    }
  };

  // The isolated bridge-world cannot call window.__testWebChannel directly
  // (globals are not shared), so it triggers actions through the shared DOM.
  document.addEventListener("__test_show_popup__", function() {
    window.__testWebChannel.showStaticPopup();
  });

  window.addEventListener("qtWebChannelReady", ensureWebChannelConnected);

  // bootstrap_page.js dispatches qtWebChannelReady before this user script runs.
  ensureWebChannelConnected();
})();
