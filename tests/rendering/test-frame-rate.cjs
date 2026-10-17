const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/overlay.js', 'utf8');
for (const [fps, renderMode, expected] of [[15, "quality", 15], [60, "quality", 60], [144, "quality", 144], [144, "balanced", 60], [144, "battery", 30]]) {
  let now = 0;
  let draws = 0;
  let callbacks = [];
  let config;
  const canvas = { width: 1920, height: 1080, style: {}, getContext: () => ({ clearRect() {} }) };
  const context = {
    document: { getElementById: () => canvas },
    performance: { now: () => now },
    requestAnimationFrame: callback => callbacks.push(callback),
    NiconiComments: class {
      constructor(canvas, threads, options) { config = options; }
      drawCanvas() { draws++; }
      destroy() {}
    },
    window: { addEventListener() {} }
  };
  vm.runInNewContext(script, context);
  context.window.commentOverlay.load([], { size: 17, opacity: 1, density: 1, speed: 1, fps, renderMode, colorStyle: 'original', fontStyle: 'default' });
  context.window.commentOverlay.sync(0, 1, true);
  for (let frame = 1; frame <= 144; frame++) {
    now = frame * 1000 / 144;
    const pending = callbacks;
    callbacks = [];
    pending.forEach(callback => callback(now));
  }
  assert.equal(draws, expected, `${fps}fps requested, ${draws} frames drawn`);
  assert.equal(config.scale, 17 / 24);
  assert.equal(config.config.fpsInterval, 1000 / fps);
  console.log(`${renderMode} ${fps}fps: ${draws} frames drawn on a simulated 144Hz display`);
}
