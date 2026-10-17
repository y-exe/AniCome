const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--disable-gpu'] });
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  try {
    await page.exposeFunction('reportError', text => errors.push(text));
    await page.addInitScript(() => { window.AndroidRenderer = { error: text => window.reportError(text) }; });
    await page.goto(pathToFileURL(path.resolve('app/src/main/assets/overlay.html')).href);
    const result = await page.evaluate(async () => {
      const options = { epoch: 'episode', size: 17, opacity: 1, speed: 1, fps: 60, density: 1, fontStyle: 'default', colorStyle: 'original' };
      const Real = window.NiconiComments;
      const instances = [];
      const frames = [];
      window.NiconiComments = class extends Real {
        constructor(...args) { super(...args); instances.push(this); }
        drawCanvas(vpos, ...args) {
          const result = super.drawCanvas(vpos, ...args);
          if (this === instances[0]) frames.push({ time: performance.now(), position: vpos });
          return result;
        }
      };
      const official = { at: 29000, body: 'OFFICIAL_KEEP_MOVING', raw: JSON.stringify({ commands: ['@60'], userId: 'official', postedAt: '2026-01-01T00:00:00Z' }) };
      window.commentOverlay.load([official], options);
      window.commentOverlay.sync(30000, 1, true);
      const first = instances[0], retained = first.comments[0];
      const extra = Array.from({ length: 20000 }, (_, i) => ({ at: 1000000 + i * 45, body: 'live-' + i }));
      window.commentOverlay.beginUpdate(1, options);
      window.commentOverlay.pushUpdate(1, [official]);
      for (let i = 0; i < extra.length; i += 128) {
        window.commentOverlay.pushUpdate(1, extra.slice(i, i + 128));
        await new Promise(resolve => setTimeout(resolve, 8));
      }
      const start = performance.now();
      const frameStart = frames.length;
      await window.commentOverlay.commitUpdate(1);
      const elapsed = performance.now() - start;
      const during = frames.slice(frameStart);
      const gaps = during.slice(1).map((f, i) => f.time - during[i].time);
      const appended = first === instances.at(-1) && first.comments[0] === retained && first.comments.length === 20001;
      const advanced = during.at(-1).position - during[0].position;
      const art = Array.from({length: 5}, (_, i) => ({ at: 29000, body: 'ART_' + i, raw: JSON.stringify({ commands: ['ca', 'shita', '@60'], userId: 'artist', postedAt: '2017-01-01T00:00:00Z' }) }));
      const allFrames = [];
      const originalDraw = Real.prototype.drawCanvas;
      Real.prototype.drawCanvas = function(vpos, ...args) { allFrames.push(performance.now()); return originalDraw.call(this, vpos, ...args); };
      await window.commentOverlay.load([official, ...art, ...extra], options);
      const artPreserved = instances.at(-1).comments.filter(c => c.content.startsWith('ART_')).every(c => c.comment.layer >= 0 && c.pluginName === 'FlashComment');
      const artAppendedWithoutReplacingOfficial = instances.at(-1) === first && first.comments[0] === retained;
      const firstArt = first.comments.find(c => c.content === 'ART_0');
      const firstLayer = firstArt.layer;
      const otherArt = art.map((c, i) => ({ ...c, body: 'OTHER_ART_' + i, raw: JSON.stringify({ commands: ['ca', 'shita', '@60'], userId: 'other-artist', source: 'jikkyo', postedAt: '2014-01-01T00:00:00Z' }) }));
      await window.commentOverlay.load([official, ...art, ...otherArt, ...extra], options);
      const anotherArtGroupKeepsExistingLayer = instances.at(-1) === first && first.comments.find(c => c.content === 'ART_0') === firstArt && firstArt.layer === firstLayer && first.comments.filter(c => c.content.startsWith('OTHER_ART_')).every(c => c.layer >= 0 && c.layer !== firstLayer);
      const rebuildGaps = allFrames.slice(1).map((time, i) => time - allFrames[i]);
      const maximumRebuildGap = Math.max(...rebuildGaps);
      window.commentOverlay.beginUpdate(2, options);
      window.commentOverlay.pushUpdate(2, [official, { at: 29000, body: 'new' }]);
      await window.commentOverlay.commitUpdate(2);
      window.commentOverlay.pushUpdate(1, [{ at: 29000, body: 'stale' }]);
      await window.commentOverlay.commitUpdate(1);
      const staleIgnored = !instances.at(-1).comments.some(c => c.content === 'stale');
      const cancelling = window.commentOverlay.load([official, ...extra, { at: 1900100, body: 'cancelled' }], options);
      window.commentOverlay.beginUpdate(3, options);
      await cancelling;
      window.commentOverlay.pushUpdate(3, [official, { at: 29000, body: 'latest' }]);
      await window.commentOverlay.commitUpdate(3);
      return { elapsed, framesDuringPlacement: during.length, maximumFrameGap: Math.max(...gaps), advanced, appended, artPreserved, artAppendedWithoutReplacingOfficial, anotherArtGroupKeepsExistingLayer, maximumRebuildGap, staleIgnored, latestCount: instances.at(-1).comments.length };
    });
    assert.ok(result.framesDuringPlacement >= 5, JSON.stringify(result));
    assert.ok(result.maximumFrameGap < 150, JSON.stringify(result));
    assert.ok(result.advanced > 10, JSON.stringify(result));
    assert.equal(result.appended, true);
    assert.equal(result.artPreserved, true);
    assert.equal(result.artAppendedWithoutReplacingOfficial, true);
    assert.equal(result.anotherArtGroupKeepsExistingLayer, true);
    assert.ok(result.maximumRebuildGap < 150, JSON.stringify(result));
    assert.equal(result.staleIgnored, true);
    assert.equal(result.latestCount, 2);
    assert.deepEqual(errors, []);
    console.log('PASS', JSON.stringify(result));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
