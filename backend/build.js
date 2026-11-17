const fs = require('fs');
const path = require('path');

function buildBundle(srcDir) {
  const files = fs.readdirSync(srcDir).sort();
  for (const file of files) {
    if (!/^[0-9]{2}-[a-z0-9-]+\.gs$/.test(file)) {
      throw new Error(`invalid source filename: ${file}`);
    }
  }
  if (!files.length) throw new Error('no .gs source files');
  return Buffer.concat(files.map(file => fs.readFileSync(path.join(srcDir, file))));
}

function main() {
  const args = process.argv.slice(2);
  let root = path.resolve(__dirname, '..');
  let check = false;
  for (let i = 0; i < args.length; i += 1) {
    if (args[i] === '--check') check = true;
    else if (args[i] === '--root' && args[i + 1]) root = path.resolve(args[++i]);
    else throw new Error(`unknown argument: ${args[i]}`);
  }
  const srcDir = path.join(root, 'backend/src');
  const bundle = buildBundle(srcDir);
  const count = fs.readdirSync(srcDir).length;
  const target = path.join(root, 'backend_additions.gs');
  if (check) {
    const current = fs.existsSync(target) ? fs.readFileSync(target) : Buffer.alloc(0);
    if (fs.existsSync(target) && bundle.equals(current)) {
      console.log(`backend_additions.gs is up to date (${count} files)`);
      return;
    }
    let offset = 0;
    let line = 1;
    while (offset < bundle.length && offset < current.length && bundle[offset] === current[offset]) {
      if (bundle[offset] === 10) line += 1;
      offset += 1;
    }
    console.error('backend_additions.gs is out of date: run node backend/build.js');
    console.error(`first differing line: ${line}`);
    process.exitCode = 1;
    return;
  }
  fs.writeFileSync(target, bundle);
  console.log(`wrote backend_additions.gs from ${count} files (${bundle.length} bytes)`);
}

module.exports = { buildBundle };
if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 2;
  }
}
