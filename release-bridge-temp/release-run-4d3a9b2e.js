import { Sandbox } from '@vercel/sandbox';

const APPS = {
  adam: { repo: 'Adam-Asmaca-Oyunu', pkg: 'com.spg.adamasmaca', ver: '1.0.70' },
  apple: { repo: 'AppleHunter', pkg: 'com.spg.applehunter', ver: '1.36' },
  blue: { repo: 'Blue-Cube-Oyunu', pkg: 'com.superpowergames.appel', ver: '1.0.69' },
  brain: { repo: 'BrainGames', pkg: 'com.ilkerbekmezci.mathroom', ver: '1.0.34' },
  cengel: { repo: 'CengelBulmaca', pkg: 'com.spg.cengelbulmaca', ver: '1.0.26' },
  chess: { repo: 'ChessGame', pkg: 'com.eog.blackchess', ver: '2.0.9', bump: true },
  gameconsole: { repo: 'GameConsole', pkg: 'com.eg.gameconsole', ver: '1.0.3' }
};

function one(v) { return Array.isArray(v) ? v[0] : v; }

export default async function handler(req, res) {
  try {
    const key = String(one(req.query?.app) || '').toLowerCase();
    const app = APPS[key];
    if (!app) return res.status(400).json({ ok: false, error: 'invalid_app' });

    const box = await Sandbox.get({ name: 'earth-games-release-vm', resume: true });
    const base = '/vercel/sandbox';
    const work = `${base}/jobs/${key}-work`;
    const bump = app.bump
      ? `sed -i "s/versionCode 106/versionCode 109/; s/versionName '2.0.6'/versionName '2.0.9'/" app/build.gradle\n`
      : '';

    const script = `set +e
rm -rf '${work}' '${base}/jobs/${key}.exit' '${base}/jobs/${key}.result.json'
( set -euo pipefail
source '${base}/release/env.sh'
GIT_ASKPASS='${base}/credentials/git-askpass.sh' GIT_TERMINAL_PROMPT=0 git clone --depth 1 --branch main 'https://github.com/ilkerbekmezcii/${app.repo}.git' '${work}'
cd '${work}'
${bump}source '${base}/credentials/signing.sh'
apply_signing '${key}'
chmod +x gradlew
./gradlew --no-daemon clean test bundleRelease
AAB='app/build/outputs/bundle/release/app-release.aab'
test -s "$AAB"
jarsigner -verify -verbose -certs "$AAB" > '${base}/jobs/${key}.sig.txt'
grep -q 'jar verified' '${base}/jobs/${key}.sig.txt'
ln -sfn '${base}/release/tools/node_modules' '${base}/release/node_modules'
node '${base}/release/play-publish.mjs' '${app.pkg}' "$AAB" production 'Earth Games ${app.ver}' | tee '${base}/jobs/${key}.result.json'
) > '${base}/jobs/${key}.log' 2>&1
rc=$?
echo "$rc" > '${base}/jobs/${key}.exit'
exit "$rc"
`;

    await box.writeFiles([{ path: `jobs/${key}-release.sh`, content: Buffer.from(script) }]);
    await box.runCommand('chmod', ['700', `jobs/${key}-release.sh`]);
    const command = await box.runCommand({ cmd: 'bash', args: [`jobs/${key}-release.sh`], detached: true });
    return res.status(202).json({ ok: true, app: key, commandId: command?.cmdId || null });
  } catch (e) {
    return res.status(500).json({ ok: false, error: e?.message || String(e) });
  }
}
