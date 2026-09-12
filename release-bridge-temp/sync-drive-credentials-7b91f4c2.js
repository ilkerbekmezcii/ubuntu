import { Sandbox } from '@vercel/sandbox';

function one(v) { return Array.isArray(v) ? v[0] : v; }
function allowed(raw) { try { const u = new URL(raw); return u.protocol === 'https:' && u.hostname.endsWith('.oaiusercontent.com'); } catch { return false; } }
async function get(raw, binary = false) { if (!allowed(raw)) throw new Error('invalid_download_url'); const r = await fetch(raw); if (!r.ok) throw new Error('download_' + r.status); return binary ? Buffer.from(await r.arrayBuffer()) : await r.text(); }
function parseEnv(text) { const out = {}; for (const raw of text.split(/\r?\n/)) { const line = raw.trim(); if (!line || line.startsWith('#') || !line.includes('=')) continue; const i = line.indexOf('='); const key = line.slice(0, i).trim(); let value = line.slice(i + 1).trim(); if (value.length >= 2 && value[0] === '"' && value[value.length - 1] === '"') { try { value = JSON.parse(value); } catch { value = value.slice(1, -1); } } out[key] = value; } return out; }
function shq(v) { return "'" + String(v).replace(/'/g, "'\\''") + "'"; }

export default async function handler(req, res) {
  try {
    const play = one(req.query?.play), jks = one(req.query?.jks), sign = one(req.query?.sign), github = one(req.query?.github);
    if (!play || !jks || !sign || !github) return res.status(400).json({ ok: false, error: 'missing_url' });
    const [playText, jksBytes, signText, githubText] = await Promise.all([get(play), get(jks, true), get(sign), get(github)]);
    const p = parseEnv(playText), senv = parseEnv(signText), g = parseEnv(githubText);
    const sa = { type:p.GOOGLE_SERVICE_ACCOUNT_TYPE, project_id:p.GOOGLE_PROJECT_ID, private_key_id:p.GOOGLE_PRIVATE_KEY_ID, private_key:p.GOOGLE_PRIVATE_KEY, client_email:p.GOOGLE_CLIENT_EMAIL, client_id:p.GOOGLE_CLIENT_ID, auth_uri:p.GOOGLE_AUTH_URI, token_uri:p.GOOGLE_TOKEN_URI, auth_provider_x509_cert_url:p.GOOGLE_AUTH_PROVIDER_CERT_URL, client_x509_cert_url:p.GOOGLE_CLIENT_CERT_URL, universe_domain:p.GOOGLE_UNIVERSE_DOMAIN || 'googleapis.com' };
    if (!sa.private_key || !sa.client_email || !g.GITHUB_TOKEN || !senv.alias || !senv.password) throw new Error('credential_parse_failed');
    const box = await Sandbox.get({ name: 'earth-games-release-vm', resume: true });
    const base='/vercel/sandbox', k=base+'/credentials/upload.jks', pw=shq(senv.password), alias=shq(senv.alias);
    const gitAskPass='#!/bin/sh\ncase "$1" in\n  *Username*) echo x-access-token ;;\n  *Password*) cat /vercel/sandbox/credentials/github-token ;;\nesac\n';
    const signing=['#!/bin/sh','apply_signing() {','  case "$1" in',`    adam) export ADAMASMACA_STORE_FILE=${shq(k)} ADAMASMACA_STORE_PASSWORD=${pw} ADAMASMACA_KEY_ALIAS=${alias} ADAMASMACA_KEY_PASSWORD=${pw} ;;`,`    apple) export APPLE_HUNTER_KEYSTORE_PATH=${shq(k)} APPLE_HUNTER_KEYSTORE_PASSWORD=${pw} APPLE_HUNTER_KEY_ALIAS=${alias} APPLE_HUNTER_KEY_PASSWORD=${pw} ;;`,`    blue) export BLUE_CUBE_KEYSTORE_PATH=${shq(k)} BLUE_CUBE_KEYSTORE_PASSWORD=${pw} BLUE_CUBE_KEY_ALIAS=${alias} BLUE_CUBE_KEY_PASSWORD=${pw} ;;`,`    brain) export BRAINGAMES_KEYSTORE_PATH=${shq(k)} BRAINGAMES_KEYSTORE_PASSWORD=${pw} BRAINGAMES_KEY_ALIAS=${alias} BRAINGAMES_KEY_PASSWORD=${pw} ;;`,`    cengel) export CENGEL_STORE_FILE=${shq(k)} CENGEL_STORE_PASSWORD=${pw} CENGEL_KEY_ALIAS=${alias} CENGEL_KEY_PASSWORD=${pw} ;;`,`    chess) export BLACKCHESS_STORE_FILE=${shq(k)} BLACKCHESS_STORE_PASSWORD=${pw} BLACKCHESS_KEY_ALIAS=${alias} BLACKCHESS_KEY_PASSWORD=${pw} ;;`,`    gameconsole) export GAMECONSOLE_STORE_FILE=${shq(k)} GAMECONSOLE_STORE_PASSWORD=${pw} GAMECONSOLE_KEY_ALIAS=${alias} GAMECONSOLE_KEY_PASSWORD=${pw} ;;`,'    *) return 2 ;;','  esac','}',''].join('\n');
    await box.writeFiles([{path:'credentials/play-service-account.json',content:Buffer.from(JSON.stringify(sa))},{path:'credentials/github-token',content:Buffer.from(g.GITHUB_TOKEN)},{path:'credentials/upload.jks',content:jksBytes},{path:'credentials/git-askpass.sh',content:Buffer.from(gitAskPass)},{path:'credentials/signing.sh',content:Buffer.from(signing)}]);
    for (const path of ['credentials/play-service-account.json','credentials/github-token','credentials/upload.jks','credentials/git-askpass.sh','credentials/signing.sh']) await box.runCommand('chmod',[path.endsWith('.sh')?'700':'600',path]);
    return res.status(200).json({ok:true,synced:{play:true,github:true,keystore:true,helpers:true},sizes:{jks:jksBytes.length}});
  } catch (e) { return res.status(500).json({ok:false,error:e?.message || String(e)}); }
}
