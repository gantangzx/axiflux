/* Stripe Checkout return bridge. Runs as an external same-origin file so it is
 * allowed by CSP script-src 'self' (inline scripts are blocked). The outcome is
 * passed via the script URL: /billing-bridge.js?outcome=success|cancel.
 *
 * On success we confirm the paid Checkout session server-to-server BEFORE
 * bouncing into the console, so the plan is activated even when the Stripe
 * webhook never arrives. Confirmation is idempotent; failures never block the
 * user from reaching the console (the scheduled sync provides a backstop).
 */
(function () {
  function outcome() {
    try {
      var s = document.currentScript && document.currentScript.src;
      if (s) {
        var v = new URL(s).searchParams.get('outcome');
        if (v === 'success' || v === 'cancel') return v;
      }
    } catch (e) { /* fall through to default */ }
    return 'success';
  }

  function sessionId() {
    try { return new URL(window.location.href).searchParams.get('session_id') || null; }
    catch (e) { return null; }
  }

  function enter() { window.location.replace('/'); }

  var result = outcome();
  try { window.sessionStorage.setItem('oc.billing.return', result); } catch (e) { /* ignore */ }

  if (result !== 'success') { enter(); return; }

  var sid = sessionId();
  if (!sid || !window.fetch) { enter(); return; }

  fetch('/api/v1/billing/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId: sid })
  }).then(function (r) {
    // Even a non-2xx (e.g. billing disabled) should not trap the user here.
    enter();
  }).catch(function () {
    // Network error: do not block the landing; scheduled sync will converge.
    enter();
  });
})();
