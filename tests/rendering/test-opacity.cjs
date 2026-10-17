const assert = require('node:assert/strict');
const path = require('node:path');
const { PNG } = require('pngjs');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({channel: 'msedge', headless: true, args: ['--disable-gpu']});
  try {
    const page = await browser.newPage({viewport: {width: 1920, height: 1080}});
    const errors = [];
    await page.exposeFunction('rendererError', message => errors.push(message));
    await page.addInitScript(() => { window.AndroidRenderer = {error: message => window.rendererError(message)}; });
    await page.goto(pathToFileURL(path.resolve('app/src/main/assets/overlay.html')).href);
    for (const opacity of [1, 0.5, 1]) {
      await page.evaluate(opacity => {
        window.commentOverlay.load([{at: 0, body: '不透明度 TEST', raw: JSON.stringify({commands: ['ue', 'white', 'nico:opacity:0.2']})}],
          {size: 24, opacity, speed: 1, fps: 60, density: 1, colorStyle: 'original', fontStyle: 'default'});
        window.commentOverlay.sync(1000, 0, true);
      }, opacity);
      await page.waitForTimeout(200);
      const result = await page.evaluate(() => {
        window.commentOverlay.drawAt(1000);
        const c = document.querySelector('canvas');
        const pixels = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
        let opaqueFill = 0, visibleStroke = 0, maxAlpha = 0, sample = null;
        for (let i = 0; i < pixels.length; i += 4) {
          maxAlpha = Math.max(maxAlpha, pixels[i + 3]);
          if (pixels[i + 3] === 255 && pixels[i] === 255) { opaqueFill++; sample ??= {x: (i / 4) % c.width, y: Math.floor(i / 4 / c.width)}; }
          if (pixels[i + 3] > 0 && pixels[i] === 0) visibleStroke++;
        }
        document.body.style.backgroundColor = '#000';
        return {opacity: getComputedStyle(c).opacity, opaqueFill, visibleStroke, maxAlpha, sample};
      });
      assert.equal(Number(result.opacity), opacity);
      assert.equal(result.maxAlpha, 255);
      assert.ok(result.opaqueFill > 50, JSON.stringify(result));
      assert.ok(result.visibleStroke > 10, JSON.stringify(result));
      const screenshot = PNG.sync.read(await page.screenshot());
      const pixel = (result.sample.y * screenshot.width + result.sample.x) * 4;
      const expected = Math.round(opacity * 255);
      assert.ok(Math.abs(screenshot.data[pixel] - expected) <= 1, 'composited text opacity');
      console.log('PASS', opacity, result);
    }
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });

