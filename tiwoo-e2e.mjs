import { chromium, devices } from 'playwright';
import fs from 'node:fs';

const base = 'https://tiwoo.vercel.app';
const out = { startedAt: new Date().toISOString(), base, public: {}, mobile: {}, pages: {}, auth: { attempted: false, available: false }, issues: [] };
const sanitize = s => String(s || '').replace(/https?:\/\/[^\s)]+/g, '<url>').slice(0, 500);
const settle = async page => {
  await page.waitForLoadState('domcontentloaded', { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(3000);
};

async function auditPage(browser, label, url, contextOptions, screenshot = true) {
  const context = await browser.newContext(contextOptions);
  const page = await context.newPage();
  const consoleErrors = [];
  const pageErrors = [];
  const failedRequests = [];
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(sanitize(m.text())); });
  page.on('pageerror', e => pageErrors.push(sanitize(e.message)));
  page.on('requestfailed', r => {
    try { failedRequests.push({ path: new URL(r.url()).pathname, error: sanitize(r.failure()?.errorText) }); }
    catch { failedRequests.push({ path: '<invalid>', error: sanitize(r.failure()?.errorText) }); }
  });

  const t0 = Date.now();
  let response;
  try { response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 }); }
  catch (e) { await context.close(); return { status: 0, navigationError: sanitize(e.message), loadMs: Date.now() - t0 }; }
  await settle(page);
  const loadMs = Date.now() - t0;
  const title = await page.title();
  const bodyText = await page.locator('body').innerText().catch(() => '');
  const links = await page.locator('a').count();
  const buttons = await page.locator('button').count();
  const inputs = await page.locator('input').count();
  const viewport = page.viewportSize();
  const overflow = await page.evaluate(() => ({
    body: document.documentElement.scrollWidth > document.documentElement.clientWidth + 2,
    width: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth
  }));
  if (screenshot) await page.screenshot({ path: `artifacts/${label}.png`, fullPage: true });
  const result = {
    status: response?.status() || 0,
    finalUrl: new URL(page.url()).pathname,
    title,
    loadMs,
    bodyChars: bodyText.length,
    links,
    buttons,
    inputs,
    viewport,
    horizontalOverflow: overflow,
    consoleErrors,
    pageErrors,
    failedRequests
  };
  await context.close();
  return result;
}

async function endpointChecks(browser) {
  const context = await browser.newContext();
  const req = context.request;
  const check = async path => req.get(`${base}${path}`).then(async r => ({ status: r.status(), ok: r.ok(), body: await r.json().catch(() => ({})) })).catch(e => ({ status: 0, ok: false, error: sanitize(e.message) }));
  const authStatus = await check('/api/tiwoo-auth?action=status');
  const authSession = await check('/api/tiwoo-auth?action=session');
  const googleStatus = await check('/api/google-login?action=status');
  await context.close();
  return {
    authStatus: { status: authStatus.status, ok: authStatus.ok, ready: Boolean(authStatus.body?.ready) },
    anonymousSession: { status: authSession.status, ok: authSession.ok, authenticated: Boolean(authSession.body?.authenticated) },
    googleStatus: { status: googleStatus.status, ok: googleStatus.ok, ready: Boolean(googleStatus.body?.ready) }
  };
}

async function tryAuthenticated(browser) {
  const username = String(process.env.TIWOO_TEST_USERNAME || '').trim();
  const password = String(process.env.TIWOO_TEST_PASSWORD || '');
  out.auth.available = Boolean(username && password);
  if (!out.auth.available) return;
  out.auth.attempted = true;

  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(sanitize(e.message)));
  page.on('console', m => { if (m.type() === 'error') errors.push(sanitize(m.text())); });
  try {
    await page.goto(base, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await settle(page);
    const usernameSelectors = [
      'input[name="username"]', 'input[autocomplete="username"]', 'input[placeholder*="kullanıcı" i]', 'input[placeholder*="username" i]', 'input[type="text"]'
    ];
    const passwordSelectors = ['input[name="password"]', 'input[autocomplete="current-password"]', 'input[type="password"]'];
    let u = null, p = null;
    for (const s of usernameSelectors) { const l = page.locator(s).first(); if (await l.count() && await l.isVisible().catch(() => false)) { u = l; break; } }
    for (const s of passwordSelectors) { const l = page.locator(s).first(); if (await l.count() && await l.isVisible().catch(() => false)) { p = l; break; } }
    if (!u || !p) throw new Error('login_fields_not_found');
    await u.fill(username);
    await p.fill(password);
    const submitCandidates = [
      page.getByRole('button', { name: /giriş|oturum|login|sign in/i }).first(),
      page.locator('button[type="submit"]').first()
    ];
    let submitted = false;
    for (const b of submitCandidates) {
      if (await b.count() && await b.isVisible().catch(() => false)) { await b.click(); submitted = true; break; }
    }
    if (!submitted) throw new Error('login_submit_not_found');
    await page.waitForTimeout(3500);

    const session = await page.request.get(`${base}/api/tiwoo-auth?action=session`).then(async r => ({ status: r.status(), body: await r.json().catch(() => ({})) }));
    const authenticated = Boolean(session.body?.authenticated);
    out.auth.loginSucceeded = authenticated;
    out.auth.sessionStatus = session.status;
    if (!authenticated) throw new Error('login_not_authenticated');

    const bodyText = await page.locator('body').innerText().catch(() => '');
    out.auth.home = {
      bodyChars: bodyText.length,
      buttons: await page.locator('button').count(),
      links: await page.locator('a').count(),
      errorsCount: errors.length
    };
    const navLabels = ['Profil', 'Arkadaşlar', 'Bildirim', 'Ayarlar', 'Mesaj'];
    out.auth.navigation = {};
    for (const label of navLabels) {
      const el = page.getByText(new RegExp(label, 'i')).first();
      const visible = await el.count() && await el.isVisible().catch(() => false);
      out.auth.navigation[label] = { visible: Boolean(visible) };
    }
  } catch (e) {
    out.auth.error = sanitize(e.message);
  } finally {
    // Public runner: never persist authenticated screenshots, cookies or storage state.
    await context.close();
  }
}

fs.mkdirSync('artifacts', { recursive: true });
const browser = await chromium.launch({ headless: true });
try {
  out.public = await auditPage(browser, 'desktop-home', base, { viewport: { width: 1440, height: 1000 } });
  out.mobile = await auditPage(browser, 'mobile-home', base, { ...devices['Pixel 7'] });
  out.pages.privacy = await auditPage(browser, 'desktop-privacy', `${base}/privacy`, { viewport: { width: 1440, height: 1000 } });
  out.pages.admin = await auditPage(browser, 'desktop-admin', `${base}/admin`, { viewport: { width: 1440, height: 1000 } });
  out.endpoints = await endpointChecks(browser);
  await tryAuthenticated(browser);
} finally {
  await browser.close();
}

for (const [label, r] of [['desktop', out.public], ['mobile', out.mobile], ['privacy', out.pages.privacy], ['admin', out.pages.admin]]) {
  if (!r || r.status !== 200) out.issues.push(`${label}: HTTP ${r?.status || 0}`);
  if (r?.navigationError) out.issues.push(`${label}: navigation error`);
  if (r?.pageErrors?.length) out.issues.push(`${label}: ${r.pageErrors.length} page errors`);
  if (r?.failedRequests?.length) out.issues.push(`${label}: ${r.failedRequests.length} failed requests`);
  if (r?.horizontalOverflow?.body) out.issues.push(`${label}: horizontal overflow ${r.horizontalOverflow.width}/${r.horizontalOverflow.client}`);
}
if (!out.endpoints.authStatus.ok) out.issues.push('auth status endpoint failed');
if (!out.endpoints.googleStatus.ok) out.issues.push('google status endpoint failed');
if (out.endpoints.anonymousSession.authenticated) out.issues.push('anonymous session unexpectedly authenticated');
if (out.auth.available && !out.auth.loginSucceeded) out.issues.push(`auth: ${out.auth.error || 'login failed'}`);
out.finishedAt = new Date().toISOString();
fs.writeFileSync('artifacts/report.json', JSON.stringify(out, null, 2));
console.log(JSON.stringify({
  desktop: { status: out.public.status, loadMs: out.public.loadMs, pageErrors: out.public.pageErrors?.length || 0, failedRequests: out.public.failedRequests?.length || 0, consoleErrors: out.public.consoleErrors?.length || 0, overflow: Boolean(out.public.horizontalOverflow?.body) },
  mobile: { status: out.mobile.status, loadMs: out.mobile.loadMs, pageErrors: out.mobile.pageErrors?.length || 0, failedRequests: out.mobile.failedRequests?.length || 0, consoleErrors: out.mobile.consoleErrors?.length || 0, overflow: Boolean(out.mobile.horizontalOverflow?.body) },
  privacy: { status: out.pages.privacy.status },
  admin: { status: out.pages.admin.status },
  endpoints: out.endpoints,
  auth: { available: out.auth.available, attempted: out.auth.attempted, loginSucceeded: Boolean(out.auth.loginSucceeded), error: out.auth.error || null },
  issues: out.issues
}, null, 2));
process.exitCode = out.issues.some(x => /HTTP 0|page errors|failed requests|overflow|endpoint failed|unexpectedly|auth:|navigation error/.test(x)) ? 1 : 0;
