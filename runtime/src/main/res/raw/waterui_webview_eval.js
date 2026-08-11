// Wrapper for WebView.evaluateJavascript.
//
// Android's evaluateJavascript hands back only a JSON-encoded value and reports
// nothing when the script throws, so a failing script was indistinguishable from
// one that returned null. Evaluating inside this wrapper produces an explicit
// envelope instead: the caller can tell success from failure, and a string result
// arrives as the string itself rather than a JSON-quoted copy of it, which is what
// every other WaterUI backend returns.
//
// __WATERUI_EVAL_SOURCE__ is replaced with the JSON-encoded user script.
(function () {
  var source = __WATERUI_EVAL_SOURCE__;
  try {
    var value = eval(source);
    if (value === undefined) {
      return JSON.stringify({ ok: true });
    }
    if (typeof value === "string") {
      return JSON.stringify({ ok: true, value: value });
    }
    try {
      return JSON.stringify({ ok: true, value: JSON.stringify(value) });
    } catch (serializationError) {
      return JSON.stringify({
        ok: false,
        unserializable: true,
        message: String(
          (serializationError && serializationError.message) || serializationError
        )
      });
    }
  } catch (error) {
    return JSON.stringify({
      ok: false,
      message: String((error && error.message) || error)
    });
  }
})();
