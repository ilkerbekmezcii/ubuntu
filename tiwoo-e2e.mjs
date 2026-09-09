import { chromium, devices } from 'playwright';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';

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
  const badResponses = [];
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(sanitize(m.text())); });
  page.on('pageerror', e => pageErrors.push(sanitize(e.message)));
  page.on('requestfailed', r => {
    try { failedRequests.push({ host: new URL(r.url()).hostname, path: new URL(r.url()).pathname, error: sanitize(r.failure()?.errorText) }); }
    catch { failedRequests.push({ host: '<invalid>', path: '<invalid>', error: sanitize(r.failure()?.errorText) }); }
  });
  page.on('response', r => {
    if (r.status() < 400) return;
    try { const u = new URL(r.url()); badResponses.push({ host: u.hostname, path: u.pathname, status: r.status(), type: r.request().resourceType() }); }
    catch { badResponses.push({ host: '<invalid>', path: '<invalid>', status: r.status(), type: r.request().resourceType() }); }
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
    failedRequests,
    badResponses
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

async function visibleControl(page, label) {
  const rx = new RegExp(label, 'i');
  for (const locator of [page.getByRole('button', { name: rx }).first(), page.getByRole('link', { name: rx }).first(), page.getByText(rx).first()]) {
    if (await locator.count() && await locator.isVisible().catch(() => false)) return locator;
  }
  return null;
}

async function authUiAudit(page) {
  const nav = {};
  const labels = ['Ana Sayfa', 'Keşfet', 'Bildirimler', 'Mesajlar', 'Kaydedilenler', 'Profil', 'Ayarlar'];
  for (const label of labels) {
    const control = await visibleControl(page, `^${label}$`);
    nav[label] = { visible: Boolean(control) };
    if (!control) continue;
    try {
      await control.click();
      await page.waitForTimeout(900);
      nav[label].clicked = true;
      nav[label].buttons = await page.locator('button:visible').count();
      nav[label].inputs = await page.locator('input:visible,textarea:visible').count();
    } catch { nav[label].clicked = false; }
  }
  return nav;
}

async function tryAuthenticated(browser) {
  const configuredUsername = String(process.env.TIWOO_TEST_USERNAME || '').trim();
  const configuredPassword = String(process.env.TIWOO_TEST_PASSWORD || '');
  out.auth.available = Boolean(configuredUsername && configuredPassword);
  out.auth.attempted = true;

  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const errors = [];
  const badResponses = [];
  page.on('pageerror', e => errors.push(sanitize(e.message)));
  page.on('console', m => { if (m.type() === 'error') errors.push(sanitize(m.text())); });
  page.on('response', r => { if (r.status() >= 400) { try { const u=new URL(r.url()); badResponses.push({host:u.hostname,path:u.pathname,status:r.status()}); } catch {} } });

  let username = configuredUsername;
  let password = configuredPassword;
  let accessToken = '';
  let refreshToken = '';
  let ephemeral = false;
  try {
    await page.goto(base, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await settle(page);

    if (out.auth.available) {
      out.auth.mode = 'configured';
      const usernameSelectors = ['input[name="username"]','input[autocomplete="username"]','input[placeholder*="kullanıcı" i]','input[placeholder*="username" i]','input[type="text"]'];
      const passwordSelectors = ['input[name="password"]','input[autocomplete="current-password"]','input[type="password"]'];
      let u = null, p = null;
      for (const s of usernameSelectors) { const l = page.locator(s).first(); if (await l.count() && await l.isVisible().catch(() => false)) { u = l; break; } }
      for (const s of passwordSelectors) { const l = page.locator(s).first(); if (await l.count() && await l.isVisible().catch(() => false)) { p = l; break; } }
      if (!u || !p) throw new Error('login_fields_not_found');
      await u.fill(username); await p.fill(password);
      const submit = await visibleControl(page, 'giriş|oturum|login|sign in');
      if (!submit) throw new Error('login_submit_not_found');
      await submit.click();
      await page.waitForTimeout(3500);
      const session = await page.evaluate(async () => {
        const s = (await window.TiwooSupabase?.sb?.auth?.getSession?.())?.data?.session;
        return s ? { ok:true, access:s.access_token ? 'yes':'', refresh:s.refresh_token ? 'yes':'' } : { ok:false };
      });
      out.auth.loginSucceeded = Boolean(session?.ok);
      if (!out.auth.loginSucceeded) throw new Error('login_not_authenticated');
    } else {
      out.auth.mode = 'ephemeral';
      ephemeral = true;
      username = `e2e_${Date.now().toString(36).slice(-8)}`.slice(0,20);
      password = `T!${randomUUID().replace(/-/g,'').slice(0,20)}a9`;
      const reg = await page.request.post(`${base}/api/tiwoo-auth`, { data: { action:'register', username, password, name:'Tiwoo E2E' } });
      const body = await reg.json().catch(() => ({}));
      out.auth.registerStatus = reg.status();
      if (!reg.ok() || !body?.session?.accessToken || !body?.session?.refreshToken) throw new Error(`ephemeral_register_${reg.status()}`);
      accessToken = String(body.session.accessToken); refreshToken = String(body.session.refreshToken);
      const set = await page.evaluate(async ({ accessToken, refreshToken }) => {
        const client = window.TiwooSupabase?.sb;
        if (!client?.auth?.setSession) return { ok:false, error:'client_missing' };
        const { data, error } = await client.auth.setSession({ access_token: accessToken, refresh_token: refreshToken });
        return { ok: !error && Boolean(data?.session), error: error?.message || '' };
      }, { accessToken, refreshToken });
      if (!set.ok) throw new Error(`ephemeral_session_${set.error || 'failed'}`);
      await page.reload({ waitUntil:'domcontentloaded', timeout:30000 });
      await settle(page);
      const current = await page.evaluate(async () => Boolean((await window.TiwooSupabase?.sb?.auth?.getSession?.())?.data?.session?.access_token));
      out.auth.loginSucceeded = current;
      if (!current) throw new Error('ephemeral_login_not_authenticated');
    }

    const appState = await page.evaluate(() => ({
      appVisible: Boolean(document.querySelector('#app') && !document.querySelector('#app').classList.contains('hidden')),
      landingVisible: Boolean(document.querySelector('#publicLanding') && !document.querySelector('#publicLanding').classList.contains('hidden')),
      mobileNavVisible: Boolean(document.querySelector('#mobileNav') && !document.querySelector('#mobileNav').classList.contains('hidden'))
    }));
    out.auth.appState = appState;
    out.auth.navigation = await authUiAudit(page);
    out.auth.errorsCount = errors.length;
    out.auth.badResponses = badResponses;

    const explore = await visibleControl(page, '^Keşfet$');
    if (explore) {
      await explore.click().catch(()=>{}); await page.waitForTimeout(700);
      const search = page.locator('input[type="search"]:visible').first();
      if (await search.count()) {
        await search.fill('kata'); await page.waitForTimeout(700);
        out.auth.search = { available:true, bodyChars:(await page.locator('body').innerText().catch(()=>'' )).length };
      } else out.auth.search = { available:false };
    }
  } catch (e) {
    out.auth.error = sanitize(e.message);
  } finally {
    if (ephemeral && accessToken && username) {
      try {
        const del = await page.request.post(`${base}/api/tiwoo-auth`, {
          headers: { authorization:`Bearer ${accessToken}` },
          data: { action:'account_delete', confirmation:username, acknowledged:true }
        });
        const body = await del.json().catch(() => ({}));
        out.auth.cleanup = { status:del.status(), deleted:Boolean(body?.deleted), authCleanupComplete:body?.authCleanupComplete !== false };
        if (!del.ok() || !body?.deleted) out.issues.push(`auth cleanup failed ${del.status()}`);
      } catch (e) { out.auth.cleanup = { status:0, deleted:false, error:sanitize(e.message) }; out.issues.push('auth cleanup failed 0'); }
    }
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
  const appFailed = (r?.failedRequests || []).filter(x => x.host === 'tiwoo.vercel.app' || x.host.endsWith('.supabase.co'));
  if (appFailed.length) out.issues.push(`${label}: ${appFailed.length} app failed requests`);
  const appBad = (r?.badResponses || []).filter(x => x.host === 'tiwoo.vercel.app' || x.host.endsWith('.supabase.co'));
  if (appBad.length) out.issues.push(`${label}: ${appBad.length} app HTTP errors`);
  if (r?.horizontalOverflow?.body) out.issues.push(`${label}: horizontal overflow ${r.horizontalOverflow.width}/${r.horizontalOverflow.client}`);
}
if (!out.endpoints.authStatus.ok) out.issues.push('auth status endpoint failed');
if (!out.endpoints.googleStatus.ok) out.issues.push('google status endpoint failed');
if (out.endpoints.anonymousSession.authenticated) out.issues.push('anonymous session unexpectedly authenticated');
if (!out.auth.loginSucceeded) out.issues.push(`auth: ${out.auth.error || 'login failed'}`);
if (out.auth.loginSucceeded && !out.auth.appState?.appVisible) out.issues.push('auth: app shell not visible after login');
out.finishedAt = new Date().toISOString();
fs.writeFileSync('artifacts/report.json', JSON.stringify(out, null, 2));
console.log(JSON.stringify({
  desktop: { status: out.public.status, loadMs: out.public.loadMs, appBad: out.public.badResponses?.filter(x=>x.host==='tiwoo.vercel.app'||x.host.endsWith('.supabase.co')) || [], appFailed: out.public.failedRequests?.filter(x=>x.host==='tiwoo.vercel.app'||x.host.endsWith('.supabase.co')) || [], overflow: Boolean(out.public.horizontalOverflow?.body) },
  mobile: { status: out.mobile.status, loadMs: out.mobile.loadMs, appBad: out.mobile.badResponses?.filter(x=>x.host==='tiwoo.vercel.app'||x.host.endsWith('.supabase.co')) || [], appFailed: out.mobile.failedRequests?.filter(x=>x.host==='tiwoo.vercel.app'||x.host.endsWith('.supabase.co')) || [], overflow: Boolean(out.mobile.horizontalOverflow?.body) },
  auth: { mode:out.auth.mode, loginSucceeded:Boolean(out.auth.loginSucceeded), appState:out.auth.appState, cleanup:out.auth.cleanup, error:out.auth.error || null },
  endpoints: out.endpoints,
  issues: out.issues
}, null, 2));
process.exitCode = out.issues.length ? 1 : 0;
