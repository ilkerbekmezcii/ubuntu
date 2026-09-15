const { chromium } = require('/home/codespace/.partner-browser-tools/node_modules/playwright');
(async()=>{
  const context = await chromium.launchPersistentContext('/home/codespace/.partner-browser-profile', {
    headless: true,
    args: ['--no-sandbox','--disable-dev-shm-usage'],
    viewport: { width: 1440, height: 900 }
  });
  const page = context.pages()[0] || await context.newPage();
  await page.goto('https://partner.microsoft.com/dashboard', { waitUntil: 'domcontentloaded', timeout: 90000 });
  await page.waitForTimeout(5000);
  console.log(JSON.stringify({
    url: page.url(),
    title: await page.title().catch(()=>''),
    text: (await page.locator('body').innerText().catch(()=>'' )).slice(0,16000)
  }, null, 2));
  await context.close();
})().catch(e=>{ console.error(e); process.exit(1); });
