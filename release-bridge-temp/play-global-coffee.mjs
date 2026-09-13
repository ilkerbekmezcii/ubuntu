import { google } from 'googleapis';

const PACKAGE = 'com.spg.adamasmaca';
const PRODUCT = 'buy_me_a_coffee';
const keyFile = '/vercel/sandbox/credentials/play-service-account.json';

const auth = new google.auth.GoogleAuth({ keyFile, scopes: ['https://www.googleapis.com/auth/androidpublisher'] });
const client = await auth.getClient();
const tokenResult = await client.getAccessToken();
const accessToken = typeof tokenResult === 'string' ? tokenResult : tokenResult?.token;
if (!accessToken) throw new Error('OAuth token unavailable');

const headers = { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' };
const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}`;
async function request(url, options = {}) {
  const response = await fetch(url, { ...options, headers: { ...headers, ...(options.headers || {}) } });
  const text = await response.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text.slice(0, 1000) }; }
  if (!response.ok) throw new Error(`${options.method || 'GET'} ${url} -> ${response.status}: ${JSON.stringify(data).slice(0, 1600)}`);
  return data;
}

const current = await request(`${base}/oneTimeProducts/${PRODUCT}`);
const regionVersion = current?.regionsVersion?.version;
if (!regionVersion) throw new Error('Missing regionsVersion');
const converted = await request(`${base}/pricing:convertRegionPrices`, {
  method: 'POST', body: JSON.stringify({ price: { currencyCode: 'TRY', units: '100' } }),
});
const regions = Object.entries(converted.convertedRegionPrices || {}).map(([regionCode, value]) => ({
  regionCode,
  price: regionCode === 'TR' ? { currencyCode: 'TRY', units: '100' } : value.price,
  availability: 'AVAILABLE',
}));
if (!regions.some((entry) => entry.regionCode === 'TR')) regions.push({ regionCode: 'TR', price: { currencyCode: 'TRY', units: '100' }, availability: 'AVAILABLE' });

const patchUrl = new URL(`${base}/onetimeproducts/${PRODUCT}`);
patchUrl.searchParams.set('updateMask', 'purchaseOptions');
patchUrl.searchParams.set('regionsVersion.version', regionVersion);
patchUrl.searchParams.set('latencyTolerance', 'LATENCY_TOLERANCE_SENSITIVE');
await request(patchUrl.toString(), {
  method: 'PATCH',
  body: JSON.stringify({
    packageName: PACKAGE,
    productId: PRODUCT,
    purchaseOptions: [{
      purchaseOptionId: 'buy',
      buyOption: { legacyCompatible: true, multiQuantityEnabled: false },
      regionalPricingAndAvailabilityConfigs: regions,
      newRegionsConfig: {
        usdPrice: converted.convertedOtherRegionsPrice?.usdPrice,
        eurPrice: converted.convertedOtherRegionsPrice?.eurPrice,
        availability: 'AVAILABLE',
      },
    }],
  }),
});
const verified = await request(`${base}/oneTimeProducts/${PRODUCT}`);
const buy = (verified.purchaseOptions || []).find((option) => option.purchaseOptionId === 'buy');
const turkey = (buy?.regionalPricingAndAvailabilityConfigs || []).find((entry) => entry.regionCode === 'TR');
console.log(JSON.stringify({ ok: true, state: buy?.state || null, regionCount: buy?.regionalPricingAndAvailabilityConfigs?.length || 0, turkey, newRegionsConfig: buy?.newRegionsConfig || null }));
