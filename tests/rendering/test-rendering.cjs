const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--disable-gpu'] });
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  try {
    await page.exposeFunction('rendererError', text => errors.push(text));
    await page.addInitScript(() => { window.AndroidRenderer = { error: text => window.rendererError(text) }; });
    await page.goto(pathToFileURL(path.resolve('app/src/main/assets/overlay.html')).href);
    const result = await page.evaluate(async () => {
      const Real = window.NiconiComments;
      window.instances = [];
      window.NiconiComments = class extends Real { constructor(...args) { super(...args); window.instances.push(this); } };
      const options = { epoch: 'episode-1', size: 17, opacity: 1, speed: .5, fps: 60, density: 1, colorStyle: 'original', fontStyle: 'default' };
      const raw = (commands, user, date = '2017-01-01T00:00:00Z') => JSON.stringify({ commands, userId: user, postedAt: date, isPremium: true });
      const art = { at: 29000, body: '  ／|、\n(ﾟ､ ｡７\n |、 ~ヽ\n じしf_,)ノ', fork: 'owner', raw: raw(['ue', 'big', 'full', 'ender', '@10'], 'artist') };
      const official = { at: 29000, body: '公式コメント', raw: raw([], 'official', '2026-01-01T00:00:00Z') };
      const live = { at: 29000, body: '生放送\n  ASCII　ART', raw: raw(['shita', '@8', 'mincho'], 'live') };
      const jikkyo = { at: 29000, body: '実況', raw: raw(['shita'], 'jikkyo', '2026-01-01T00:00:00Z') };
      const pixels = () => document.querySelector('#comments').getContext('2d').getImageData(0, 0, 1920, 1080).data;
      window.commentOverlay.load([art, official], options);
      window.commentOverlay.sync(30000, 0, true);
      window.commentOverlay.drawAt(30000);
      const first = window.instances.at(-1), retained = first.comments.slice();
      const beforeImage = document.querySelector('#comments').toDataURL();
      const oldCoordinates = retained.map(c => [c.posY, c.loc, c.width, c.height]);
      const before = pixels().slice(0, 1920 * 300 * 4);
      const beforeArt = retained.find(c => c.content === art.body);
      window.commentOverlay.load([live, art, jikkyo, official], options);
      const sameRenderer = first === window.instances.at(-1);
      const retainedObjects = retained.every((c, i) => first.comments[i] === c);
      const timelineContainsOld = retained.map(c => first.timeline[3000]?.includes(c));
      const afterImage = document.querySelector('#comments').toDataURL();
      const newCoordinates = retained.map(c => [c.posY, c.loc, c.width, c.height]);
      const after = pixels().slice(0, 1920 * 300 * 4);
      const stablePixels = before.every((v, i) => v === after[i]);
      const count = first.comments.length;
      window.commentOverlay.load([live, art, jikkyo, official], options);
      const noDuplicates = first.comments.length === count;
      window.commentOverlay.load([live, art, jikkyo, official], { ...options, opacity: .5, fps: 144 });
      const presentationDidNotRebuild = first === window.instances.at(-1);
      let swapAlreadyPainted = false;
      const currentCanvas = document.querySelector('#comments');
      const replace = currentCanvas.replaceWith.bind(currentCanvas);
      currentCanvas.replaceWith = next => {
        const rgba = next.getContext('2d').getImageData(0, 0, 1920, 1080).data;
        swapAlreadyPainted = rgba.some((v, i) => i % 4 === 3 && v > 0);
        replace(next);
      };
      window.commentOverlay.load([art, official], options);
      const artMode = beforeArt?.pluginName;
      const currentArt = window.instances.at(-1).comments.find(c => c.content === art.body);
      const unchangedBody = currentArt.content === art.body;
      const originalDuration = currentArt.long;
      const mainDuration = window.instances.at(-1).comments.find(c => c.content === official.body).long;
      const canvas = document.createElement('canvas'); canvas.width = 1920; canvas.height = 1080; canvas.getContext('2d');
      const expected = new Real(canvas, [{ id: 0, vpos: 2900, content: art.body, date: 1483228800, owner: true, premium: true, mail: ['nico:opacity:1', 'ue', 'big', 'full', 'ender', '@10'], user_id: 0, layer: -2 }], {
        format: 'formatted', mode: 'default', scale: 17 / 24, config: { contextStrokeOpacity: .62, contextFillLiveOpacity: 1, commentDrawRange: 1920, commentDrawPadding: 0, collisionRange: { left: 0, right: 1920 }, fpsInterval: 1000 / 60 }
      });
      expected.drawCanvas(3000);
      window.commentOverlay.load([art], options); window.commentOverlay.drawAt(30000);
      const aaActual = pixels(), aaExpected = canvas.getContext('2d').getImageData(0, 0, 1920, 1080).data;
      const faithfulAA = aaActual.every((v, i) => v === aaExpected[i]);
      await window.commentOverlay.load([art, ...Array.from({ length: 129 }, (_, i) => ({ at: 1000000 + i * 100, body: 'future-' + i }))], { ...options, epoch: 'bulk-aa' });
      window.commentOverlay.drawAt(30000);
      const faithfulBulkAA = pixels().every((v, i) => v === aaExpected[i]);
      const partCount = window.instances.length;
      const pieces = Array.from({length: 5}, (_, i) => ({at: 29000, body: '組み合わせアート ' + i, raw: raw(['shita', 'ca', 'full', '@10'], 'ca-author')}));
      window.commentOverlay.load([art, ...pieces], options);
      const groupedArtRebuilt = window.instances.length === partCount + 1;
      const groupedComments = window.instances.at(-1).comments.filter(c => c.content.startsWith('組み合わせアート'));
      const artGroupingPreserved = groupedComments.every(c => c.comment.layer >= 0);
      window.commentOverlay.load([art, { ...art, body: '別の固定アート' }], { ...options, density: .25, fontStyle: 'gothic' });
      const artSurvivesDensity = window.instances.at(-1).comments.length === 2 && window.instances.at(-1).comments.every(c => c.pluginName === 'FlashComment');
      window.commentOverlay.load([art, { ...art, body: 'battery art' }, ...Array.from({length: 20}, (_, i) => ({at: 29000, body: 'ordinary-' + i}))], { ...options, renderMode: 'battery' });
      const batteryComments = window.instances.at(-1).comments;
      const batteryKeepsArt = batteryComments.filter(c => c.pluginName === 'FlashComment').length === 2;
      const batteryReducesOrdinary = batteryComments.filter(c => c.content.startsWith('ordinary-')).length === 10;
      const translucent = { ...art, raw: raw(['ue', 'big', 'full', 'ender', '@10', 'nico:opacity:0.25'], 'artist') };
      window.commentOverlay.load([translucent], options); window.commentOverlay.drawAt(30000);
      const alpha = pixels().filter((_, i) => i % 4 === 3);
      const maximumAlpha = alpha.reduce((max, value) => Math.max(max, value), 0);
      const authorOpacityPreserved = maximumAlpha >= 50 && maximumAlpha <= 64;
      const entryBounds = [];
      for (const speed of [.25, .5, 1, 1.5]) {
        window.commentOverlay.load([{ at: 10000, body: '画面外から', raw: raw([], 'flow', '2026-01-01T00:00:00Z') }], { ...options, epoch: 'flow-' + speed, speed });
        window.commentOverlay.drawAt(9050);
        const rgba = pixels(); let left = 1920, right = -1;
        for (let i = 3; i < rgba.length; i += 4) if (rgba[i]) { const x = ((i - 3) / 4) % 1920; left = Math.min(left, x); right = Math.max(right, x); }
        entryBounds.push({ speed, left, right });
      }
      return { beforeImage, afterImage, oldCoordinates, newCoordinates, timelineContainsOld, sameRenderer, retainedObjects, stablePixels, noDuplicates, presentationDidNotRebuild, swapAlreadyPainted, artMode, unchangedBody, originalDuration, mainDuration, faithfulAA, faithfulBulkAA, artSurvivesDensity, groupedArtRebuilt, artGroupingPreserved, batteryKeepsArt, batteryReducesOrdinary, authorOpacityPreserved, entryBounds };
    });
    for (const key of ['beforeImage', 'afterImage']) { require('node:fs').writeFileSync('artifacts/render-0240-' + key + '.png', Buffer.from(result[key].split(',')[1], 'base64')); delete result[key]; }
    assert.deepEqual(result.oldCoordinates, result.newCoordinates);
    assert.ok(result.timelineContainsOld.every(Boolean));
    for (const key of ['sameRenderer', 'retainedObjects', 'stablePixels', 'noDuplicates', 'presentationDidNotRebuild', 'swapAlreadyPainted', 'unchangedBody', 'faithfulAA', 'faithfulBulkAA', 'artSurvivesDensity', 'groupedArtRebuilt', 'artGroupingPreserved', 'authorOpacityPreserved', 'batteryKeepsArt', 'batteryReducesOrdinary']) assert.equal(result[key], true, key);
    assert.equal(result.artMode, 'FlashComment');
    assert.equal(result.originalDuration, 1000);
    assert.equal(result.mainDuration, 700);
    result.entryBounds.forEach(b => assert.ok(b.left >= 1840 && b.right >= b.left, JSON.stringify(b)));
    assert.deepEqual(errors, []);
    console.log('PASS', JSON.stringify(result));
    await page.screenshot({ path: 'artifacts/render-0240-entry.png' });
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
