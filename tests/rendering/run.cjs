const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '../..');
const filters = process.argv.slice(2);
const files = fs.readdirSync(__dirname).filter(name =>
  /^test-.*\.cjs$/.test(name) && (!filters.length || filters.some(filter => name.includes(filter)))
).sort();
if (!files.length) {
  console.error('No matching rendering tests.');
  process.exit(1);
}
for (const name of files) {
  console.log(`Running ${name}`);
  const result = spawnSync(process.execPath, [path.join(__dirname, name)], { cwd: root, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
}
console.log(`Passed ${files.length} rendering test files.`);
