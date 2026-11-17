const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const srcDir = path.join(root, 'backend/src');
const buildScript = path.join(root, 'backend/build.js');
const filenamePattern = /^[0-9]{2}-[a-z0-9-]+\.gs$/;

function withTempTree(test) {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'backend-bundle-'));
  try {
    fs.mkdirSync(path.join(temp, 'backend'));
    fs.cpSync(srcDir, path.join(temp, 'backend/src'), { recursive: true });
    fs.copyFileSync(path.join(root, 'backend_additions.gs'), path.join(temp, 'backend_additions.gs'));
    test(temp);
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
}

function check(tree) {
  return spawnSync(process.execPath, [buildScript, '--check', '--root', tree], { encoding: 'utf8', cwd: os.tmpdir() });
}

function testCommittedBundleEqualsSources() {
  const { buildBundle } = require('../backend/build');
  assert.ok(buildBundle(srcDir).equals(fs.readFileSync(path.join(root, 'backend_additions.gs'))));
}

function testExactlyFourteenNamedSources() {
  const files = fs.readdirSync(srcDir);
  assert.equal(files.filter(file => file.endsWith('.gs')).length, 14);
  files.forEach(file => assert.match(file, filenamePattern));
}

function testSectionsBeginAtBanners() {
  const files = fs.readdirSync(srcDir).sort();
  assert.equal(files.length, 14);
  files.filter(file => file !== '00-header.gs').forEach(file => {
    const lines = fs.readFileSync(path.join(srcDir, file), 'utf8').split('\n');
    assert.equal(lines[0], '// -----------------------------------------------------------------------------', file);
    assert.match(lines[1], /^\/\/ \S.*$/, file);
  });
}

function testCheckDetectsTamperedBundle() {
  assert.equal(check(root).status, 0);
  withTempTree(temp => {
    const bundlePath = path.join(temp, 'backend_additions.gs');
    const tampered = fs.readFileSync(bundlePath);
    tampered[0] ^= 1;
    fs.writeFileSync(bundlePath, tampered);
    const { buildBundle } = require('../backend/build');
    assert.ok(!buildBundle(path.join(temp, 'backend/src')).equals(tampered));
    const result = check(temp);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /backend_additions\.gs is out of date: run node backend\/build\.js/);
    assert.match(result.stderr, /first differing line: 1\b/);
    assert.ok(fs.readFileSync(bundlePath).equals(tampered));
  });
}

function testStrayFilenameRefusesBuild() {
  withTempTree(temp => {
    fs.writeFileSync(path.join(temp, 'backend/src/zz.gs'), 'stray');
    const result = spawnSync(process.execPath, [buildScript, '--root', temp], { encoding: 'utf8' });
    assert.equal(result.status, 2);
    assert.match(result.stderr, /invalid source filename: zz\.gs/);
  });
}

const tests = [
  testCommittedBundleEqualsSources,
  testExactlyFourteenNamedSources,
  testSectionsBeginAtBanners,
  testCheckDetectsTamperedBundle,
  testStrayFilenameRefusesBuild,
];
let failures = 0;
tests.forEach(test => {
  try {
    test();
  } catch (error) {
    failures += 1;
    console.error(`${test.name}: ${error.message}`);
  }
});
if (failures) {
  console.error(`${failures} failed`);
  process.exitCode = 1;
}
else console.log('backend bundle tests passed');
