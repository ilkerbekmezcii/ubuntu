const crypto = require('crypto');

function b64url(value) {
  return Buffer.from(value).toString('base64url');
}

function normalizePrivateKey(value) {
  let key = String(value || '').trim();
  if (key.startsWith('"') && key.endsWith('"')) {
    try { key = JSON.parse(key); } catch {}
  }
  return key.replace(/\\n/g, '\n');
}

async function googleAccessToken() {
  const email = process.env.GOOGLE_CLIENT_EMAIL;
  const privateKey = normalizePrivateKey(process.env.GOOGLE_PRIVATE_KEY);
  const tokenUri = process.env.GOOGLE_TOKEN_URI || 'https://oauth2.googleapis.com/token';
  if (!email || !privateKey) throw new Error('Google service account environment is incomplete');

  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const payload = b64url(JSON.stringify({
    iss: email,
    scope: 'https://www.googleapis.com/auth/androidpublisher',
    aud: tokenUri,
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = `${header}.${payload}`;
  const signer = crypto.createSign('RSA-SHA256');
  signer.update(unsigned);
  signer.end();
  const assertion = `${unsigned}.${signer.sign(privateKey).toString('base64url')}`;

  const response = await fetch(tokenUri, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion,
    }),
  });
  const data = await response.json();
  if (!response.ok || !data.access_token) throw new Error(`OAuth failed: ${response.status}`);
  return data.access_token;
}

module.exports = async function handler(req, res) {
  try {
    const PACKAGE = 'com.spg.adamasmaca';
    const PRODUCT = 'buy_me_a_coffee';
    const accessToken = await googleAccessToken();
    const headers = { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' };
    const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}`;

    async function request(url, options = {}) {
      const response = await fetch(url, { ...options, headers: { ...headers, ...(options.headers || {}) } });
      const text = await response.text();
      let data = {};
      try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text.slice(0, 500) }; }
      if (!response.ok) throw new Error(`${options.method || 'GET'} failed ${response.status}: ${JSON.stringify(data).slice(0, 1200)}`);
      return data;
    }

    const current = await request(`${base}/oneTimeProducts/${PRODUCT}`);
    const regionVersion = current?.regionsVersion?.version;
    if (!regionVersion) throw new Error('Missing regionsVersion');

    const converted = await request(`${base}/pricing:convertRegionPrices`, {
      method: 'POST',
      body: JSON.stringify({ price: { currencyCode: 'TRY', units: '100' } }),
    });

    const regions = Object.entries(converted.convertedRegionPrices || {}).map(([regionCode, value]) => ({
      regionCode,
      price: regionCode === 'TR' ? { currencyCode: 'TRY', units: '100' } : value.price,
      availability: 'AVAILABLE',
    }));
    if (!regions.some((entry) => entry.regionCode === 'TR')) {
      regions.push({ regionCode: 'TR', price: { currencyCode: 'TRY', units: '100' }, availability: 'AVAILABLE' });
    }

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

    res.status(200).json({
      ok: true,
      state: buy?.state || null,
      regionCount: buy?.regionalPricingAndAvailabilityConfigs?.length || 0,
      turkey,
      newRegionsConfig: buy?.newRegionsConfig || null,
    });
  } catch (error) {
    res.status(500).json({ ok: false, error: error instanceof Error ? error.message : String(error) });
  }
};
