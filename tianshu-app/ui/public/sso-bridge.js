/* SSO login completion bridge. External same-origin file so it satisfies CSP
 * script-src 'self' (inline scripts are blocked). The JWT arrives in the URL
 * hash; we persist it and bounce into the console.
 */
(function () {
  function fail(message) {
    var spin = document.getElementById('spin');
    var title = document.getElementById('title');
    var sub = document.getElementById('sub');
    if (spin) spin.style.display = 'none';
    if (title) {
      title.textContent = '登录失败';
      title.classList.add('err');
    }
    if (sub) sub.textContent = message || '请返回重新登录';
  }
  try {
    var hash = window.location.hash
      ? (window.location.hash.charAt(0) === '#' ? window.location.hash.slice(1) : window.location.hash)
      : '';
    var token = new URLSearchParams(hash).get('token');
    if (!token) { fail('未收到登录凭证'); return; }
    window.localStorage.setItem('oc.token', token);
    window.sessionStorage.removeItem('oc.sso.error');
    window.location.replace('/');
  } catch (e) {
    fail(e && e.message ? e.message : '无法保存登录状态');
  }
})();
