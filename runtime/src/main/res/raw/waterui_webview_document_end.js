// Holds one `ScriptInjectionTime.DocumentEnd` script until the DOM is built.
//
// The upper-case placeholder below is replaced with the script itself. It is not
// named in a comment: substitution replaces every occurrence, and a script is
// many lines of source that a `//` line could not contain.
//
// WaterUI's contract is WebKit's: a document-end script runs after the DOM is
// constructed and *before* subresources are fetched. Android has no such
// injection point, and its nearest client callback — `onPageFinished` — fires
// after the whole `load` event, so a script that was meant to decorate the DOM
// ran once every image on the page had already been downloaded. The script is
// therefore injected at document start and held until `DOMContentLoaded`, which
// is the same moment WebKit means.
(function () {
  var run = function () {
    __WATERUI_DOCUMENT_END_SCRIPT__
  };
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", run, { once: true });
    return;
  }
  run();
})();
