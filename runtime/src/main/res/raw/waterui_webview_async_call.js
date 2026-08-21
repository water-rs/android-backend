// Runs one `call_async_javascript` body and reports the value its promise
// settles with.
//
// Android has no awaiting evaluation API: `WebView.evaluateJavascript` hands
// back the *synchronous* value of the script, and the shared wrapper in
// `js/eval.js` is `async`, so the envelope every typed evaluation needs would
// cross as `{}` — which is what made `WebView::eval`, `WebView::exec` and every
// mirrored-state push fail. The promise is therefore awaited here, in
// JavaScript, and the settled value is posted back through the web message
// listener the wrapper matches to the waiting native callback by id.
//
// The wrapper substitutes the four upper-case placeholders below: the object
// results are posted to, the id of the native callback to settle, the value a
// launched call evaluates to, and the async function body itself. They are never
// named in a comment, because substitution replaces every occurrence and the
// body is many lines of source: spliced into a `//` line it would take the rest
// of the script with it.
(function () {
  var id = __WATERUI_ASYNC_CALL_ID__;
  var report = function (ok, value) {
    __WATERUI_ASYNC_RESULT_OBJECT__.postMessage(
      JSON.stringify({ id: id, ok: ok, value: value })
    );
  };
  var fail = function (error) {
    report(false, String((error && error.message) || error));
  };
  try {
    (async function () {
      __WATERUI_ASYNC_CALL_BODY__
    })().then(function (value) {
      // The wrapper always resolves with its JSON envelope as a string, and the
      // value crosses to Rust unmodified. Anything else is a broken contract
      // rather than a result to reshape into one.
      if (typeof value !== "string") {
        report(
          false,
          "WaterUI evaluation resolved with a " +
            typeof value +
            " rather than the shared wrapper's envelope"
        );
        return;
      }
      report(true, value);
    }, fail);
  } catch (error) {
    fail(error);
  }
  // Read by the wrapper: a script that fails to parse never runs a line of the
  // above, so nothing would ever settle the call. It is the one failure the
  // promise cannot report.
  return "__WATERUI_ASYNC_CALL_SENTINEL__";
})();
