const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--disable-gpu'] });
  try {
    for (const initial of [false, true]) {
      const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
      await page.goto(pathToFileURL(path.resolve('app/src/main/assets/overlay.html')).href);
      const result = await page.evaluate(async initial => {
      const Real = window.NiconiComments, entered = [], instances = [];
      window.NiconiComments = class extends Real {
        constructor(...args) { super(...args); instances.push(this); }
        addComments(...comments) {
          const before = this.comments.length; super.addComments(...comments);
          for (const c of this.comments.slice(before)) if (c.content.startsWith('RIGHT_')) {
            const draw = c.draw;
            c.draw = function(vpos, ...args) {
              const x = Real.internal.utils.getPosX(this.comment, vpos, this.config, false);
              if (!this.firstEntry && x < 1920 && x + this.width > 0) { this.firstEntry = true; entered.push({ body: this.content, x, delay: (vpos - this.vpos + 100) * 10 }); }
              return draw.call(this, vpos, ...args);
            };
          }
          const until = performance.now() + 4;
          while (performance.now() < until) {}
        }
      };
      const options = { epoch: 'entry', size: 17, opacity: 1, speed: .5, fps: 60, density: 1 };
      const old = { at: 119000, body: 'existing' };
      if (!initial) window.commentOverlay.load([old], options);
      window.commentOverlay.sync(120000, 1, true);
      const expired = Array.from({ length: 5000 }, (_, i) => ({ at: i * 4, body: 'expired-' + i }));
      const upcoming = Array.from({ length: 30 }, (_, i) => ({ at: 121000 + i * 1000, body: 'RIGHT_' + i }));
      const loading = window.commentOverlay.load([...expired, old, ...upcoming], options);
      await new Promise(resolve => setTimeout(resolve, 12000));
      window.commentOverlay.beginUpdate(99, options);
      await loading;
      return { initial, entered, sameRenderer: instances.length === 1 };
      }, initial);
      console.log(JSON.stringify(result));
      assert.ok(result.entered.length >= 10, 'Upcoming comments were starved by expired comments');
      assert.ok(result.entered.every(c => c.x >= 1850 && c.delay < 250), 'Comments first appeared after entering the viewport');
      assert.equal(result.sameRenderer, true);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
