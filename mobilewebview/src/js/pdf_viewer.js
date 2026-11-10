// The PDF viewer page (ADR 0008). Images render at the device's pixel width
// and scale with pinch zoom. Each text run is a transparent span over its
// place on the page, in units of the page's width, stretched to the width the
// page draws it at; line breaks between runs keep copied text in lines. The
// text of every page loads, visible pages first, so find-in-page covers the
// whole document. The host fills in the arguments on the last line.
(function (base, maxWidth, withText) {
  'use strict';
  var pages = document.querySelectorAll('.page');
  var width = Math.round(Math.min(
    Math.max(document.documentElement.clientWidth, 1) * (window.devicePixelRatio || 1),
    maxWidth));
  for (var i = 0; i < pages.length; i++) {
    pages[i].querySelector('img').src = base + i + '?w=' + width;
  }
  if (!withText) {
    return;
  }

  var ctx = document.createElement('canvas').getContext('2d');

  // runs: [left, top, right, bottom, text, separator] in page units.
  function lay(page, runs) {
    var pw = +page.getAttribute('data-w');
    var ph = +page.getAttribute('data-h');
    var fragment = document.createDocumentFragment();
    for (var j = 0; j < runs.length; j++) {
      var r = runs[j];
      var h = r[3] - r[1];
      if (h <= 0 || r[2] <= r[0]) {
        continue;
      }
      ctx.font = h + 'px sans-serif';
      var measured = ctx.measureText(r[4]).width;
      var span = document.createElement('span');
      span.textContent = r[4] + (r[5] === ' ' ? ' ' : '');
      span.style.left = r[0] * 100 / pw + '%';
      span.style.top = r[1] * 100 / ph + '%';
      span.style.fontSize = 'calc(' + h + '*100cqw/' + pw + ')';
      if (measured > 0) {
        span.style.transform = 'scaleX(' + (r[2] - r[0]) / measured + ')';
      }
      fragment.appendChild(span);
      if (r[5] === '\n') {
        fragment.appendChild(document.createElement('br'));
      }
    }
    fragment.appendChild(document.createElement('br'));
    page.querySelector('.text').appendChild(fragment);
  }

  var queue = [];
  var busy = 0;
  for (i = 0; i < pages.length; i++) {
    queue.push(i);
  }
  var seen = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      var k = queue.indexOf(+entry.target.getAttribute('data-page'));
      if (entry.isIntersecting && k > 0) {
        queue.unshift(queue.splice(k, 1)[0]);
      }
    });
  });
  for (i = 0; i < pages.length; i++) {
    seen.observe(pages[i]);
  }

  function load(n) {
    busy++;
    fetch(base + n + '/text')
      .then(function (response) { return response.ok ? response.json() : []; })
      .then(function (runs) { lay(pages[n], runs); }, function () {})
      .then(function () { busy--; pump(); });
  }

  function pump() {
    while (busy < 2 && queue.length) {
      load(queue.shift());
    }
    if (!queue.length) {
      seen.disconnect();
    }
  }

  pump();
})(%BASE%, %MAX_WIDTH%, %WITH_TEXT%);
