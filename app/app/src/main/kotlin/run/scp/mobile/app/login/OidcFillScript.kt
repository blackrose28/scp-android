package run.scp.mobile.app.login

/**
 * The page script for the automated Boundary OIDC login, ported from the desktop
 * `boundary_oidc_init_script` (field selectors and submit logic are the same). Differences:
 * it runs once per call instead of on its own timer, so the app decides when and where it runs
 * (see [isInjectableUrl]) and can hand it a fresh TOTP each time; and it returns what it did
 * (`"password"`, `"otp"`, `"next"` or `""`) so the app can cap retries ([FillGovernor]).
 * The result never contains a credential.
 */
fun oidcFillScript(username: String, password: String, otp: String): String =
    "(function(cfg){$BODY})({username:${jsString(username)},password:${jsString(password)},otp:${jsString(otp)}});"

/** [s] as a JS string literal that is safe in any context (no raw quotes, backslashes, `<`, `>`, `&`, line separators). */
fun jsString(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' || c == '<' || c == '>' || c == '&' || c == '\'' || c == ' ' || c == ' ' ->
                sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
    }
    return sb.append('"').toString()
}

private const val BODY = """
  var visible = function(el){ return !!el && !el.disabled && el.type !== "hidden" && el.offsetParent !== null; };
  var findFirst = function(selectors){
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (visible(el)) return el;
    }
    return null;
  };
  var setValue = function(el, value){
    if (!el || !value) return false;
    el.focus();
    el.value = value;
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
    return true;
  };
  var submit = function(el){
    if (!el) return false;
    var form = el.closest("form");
    if (form) {
      if (typeof form.requestSubmit === "function") form.requestSubmit(); else form.submit();
      return true;
    }
    var btn = findFirst(["button[type=submit]","input[type=submit]","button[id*=login i]","button[name*=login i]",
      "button[id*=verify i]","button[name*=verify i]","button"]);
    if (btn) { btn.click(); return true; }
    return false;
  };
  var pwd = findFirst(["input[type=password]","input[name*=pass i]","input[id*=pass i]"]);
  var otpField = findFirst(["input[autocomplete='one-time-code']","input[name*=otp i]","input[id*=otp i]",
    "input[name*=totp i]","input[id*=totp i]","input[name*=code i]","input[id*=code i]"]);
  if (otpField && cfg.otp && !otpField.value) {
    setValue(otpField, cfg.otp);
    submit(otpField);
    return "otp";
  }
  var user = findFirst(["input[autocomplete=username]","input[type=email]","input[name*=user i]","input[id*=user i]",
    "input[name*=email i]","input[id*=email i]","input[type=text]"]);
  if (user && cfg.username && !user.value) setValue(user, cfg.username);
  if (pwd && cfg.password && !pwd.value) {
    setValue(pwd, cfg.password);
    submit(pwd);
    return "password";
  }
  if (user && cfg.username && !pwd) {
    var next = findFirst(["button[type=submit]","input[type=submit]","button[id*=next i]","button[name*=next i]"]);
    if (next) { next.click(); return "next"; }
  }
  return "";
"""
