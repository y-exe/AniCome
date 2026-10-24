const assert = require('node:assert/strict');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require('playwright');
(async () => {
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const page = await browser.newPage({viewport:{width:2800,height:1260}});
    await page.goto(pathToFileURL(path.resolve('app/src/main/assets/overlay.html')).href);
    const result = await page.evaluate(() => {
      const Real = NiconiComments; let created = 0;
      window.NiconiComments = class extends Real { constructor(...args) {super(...args);created++;} };
      window.commentOverlay.load([{at:1000,body:'existing comment'}], {epoch:'viewport',size:14,opacity:.75,speed:.7,density:1,fps:60});
      const measure = () => {
        const rect = document.querySelector('#commentViewport').getBoundingClientRect();
        const canvas = document.querySelector('canvas').getBoundingClientRect();
        return {left:rect.left,top:rect.top,width:rect.width,height:rect.height,canvasLeft:canvas.left,canvasRight:canvas.right};
      };
      const frames = [];
      for (const [left,right] of [[361,2601],[199,2439],[280,2520]]) {
        window.commentOverlay.setViewport({left:left/2800,top:0,width:(right-left)/2800,height:1});
        window.commentOverlay.drawAt(1000);
        frames.push(measure());
      }
      window.commentOverlay.setViewport({left:-1,top:0,width:1,height:1});
      return {frames, fallback:measure(), created};
    });
    for (const [index,left] of [361,199,280].entries()) {
      const frame = result.frames[index];
      assert.ok(Math.abs(frame.left-left)<.1, JSON.stringify(frame));
      assert.ok(Math.abs(frame.width-2240)<.1);
      assert.equal(frame.top,0); assert.equal(frame.height,1260);
      assert.ok(Math.abs(frame.canvasLeft-left)<.1);
      assert.ok(Math.abs(frame.canvasRight-(left+2240))<.1);
    }
    assert.equal(result.created,1,'Adjusting clipping must retain the renderer and existing comments');
    assert.ok(Math.abs(result.fallback.left-280)<.1);
    await page.setViewportSize({width:1260,height:2800});
    const portrait = await page.evaluate(() => {
      window.commentOverlay.setViewport({left:.1,top:.1,width:.8,height:.5});
      const rect=document.querySelector('#commentViewport').getBoundingClientRect();
      return {left:rect.left,top:rect.top,width:rect.width,height:rect.height};
    });
    assert.deepEqual(portrait,{left:0,top:0,width:1260,height:2800});
    console.log('Detected video clipping, both cutout sides, fallback, portrait and renderer continuity passed.');
  } finally { await browser.close(); }
})().catch(error => {console.error(error);process.exitCode=1;});
