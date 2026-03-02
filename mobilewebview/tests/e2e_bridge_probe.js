/* eslint-disable no-undef */
(function () {
  if (!window.qt || !window.qt.webChannelTransport) {
    return;
  }

  window.qt.webChannelTransport.onmessage = function (event) {
    window.qt.webChannelTransport.send(
      JSON.stringify({
        kind: "ack",
        echo: String(event.data),
      })
    );
  };

  window.qt.webChannelTransport.send(
    JSON.stringify({
      kind: "ready",
    })
  );
})();
