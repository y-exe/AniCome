const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

(async () => {
  assert.ok(process.argv[2], 'Specify an exported comment JSON file');
  const data = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const comments = Array.isArray(data) ? data : data.comments;
  const duration = Number(process.argv[3] || 1417);
  assert.ok(Array.isArray(comments) && duration > 0 && duration <= 86400);
  const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--disable-gpu'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    await page.goto(pathToFileURL(path.resolve('app/src/main/assets/overlay.html')).href);
    const report = await page.evaluate(async ({ comments, duration }) => {
      const Real = window.NiconiComments, instances = [], errors = [];
      window.AndroidRenderer = { error: message => errors.push(message) };
      window.NiconiComments = class extends Real { constructor(...args) { super(...args); instances.push(this); } };
      await window.commentOverlay.load(comments, { epoch: 'audit', size: 17, opacity: 1, speed: .5, fps: 60, density: 1 });
      const renderer = instances.at(-1), drawComments = renderer._drawComments;
      renderer._drawComments = () => 0;
      const missing = [];
      for (let second = 0; second < duration; second++) {
        const vpos = second * 100;
        renderer.drawCanvas(vpos, true);
        const registered = new Set(renderer.timeline[vpos] || []);
        const count = renderer.comments.filter(c => !c.invisible && c.vpos <= vpos && c.vpos + c.long > vpos && !registered.has(c)).length;
        if (count) missing.push({ second, count });
      }
      renderer._drawComments = drawComments;
      let imageCount = 0;
      const drawImage = renderer.renderer.drawImage;
      renderer.renderer.drawImage = function (...args) { imageCount++; return drawImage.apply(this, args); };
      const frames = [];
      for (let second = 30; second < duration; second += 60) {
        imageCount = 0; renderer.drawCanvas(second * 100, true);
        frames.push({ second, visibleImages: imageCount, registered: (renderer.timeline[second * 100] || []).length });
      }
      return {
        inputCount: comments.length, libraryCount: renderer.comments.length, missing, errors, frames,
        minuteCounts: Array.from({ length: Math.ceil(duration / 60) }, (_, i) => comments.filter(c => c.at >= i * 60000 && c.at < (i + 1) * 60000).length)
      };
    }, { comments, duration });
    assert.deepEqual(report.missing, []);
    assert.deepEqual(report.errors, []);
    console.log(JSON.stringify(report, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
