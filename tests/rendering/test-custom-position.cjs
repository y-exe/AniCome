const assert = require('node:assert/strict');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require('playwright');
(async () => {
  const browser = await chromium.launch({channel:'msedge', headless:true});
  try {
    const page = await browser.newPage({viewport:{width:900,height:2000}});
    await page.goto(pathToFileURL(path.resolve('app/src/main/assets/overlay.html')).href);
    const result = await page.evaluate(() => {
      const Real = NiconiComments; let created = 0;
      window.NiconiComments = class extends Real { constructor(...args) {super(...args);created++;} };
      const comments = [{at:1000,body:'位置の確認'}];
      const options = {epoch:'position',size:14,opacity:.75,speed:.7,density:1,fps:60,renderMode:'balanced'};
      const bounds = [];
      for (const customY of [0,.231,.5,1]) {
        window.commentOverlay.load(comments,{...options,position:'custom',customY});
        window.commentOverlay.drawAt(1000);
        const rect = document.querySelector('#comments').getBoundingClientRect();
        bounds.push({customY,top:rect.top,height:rect.height,viewport:innerHeight});
      }
      return {bounds,created};
    });
    for (const b of result.bounds) assert.ok(Math.abs(b.top-(b.viewport-b.height)*b.customY)<1,JSON.stringify(b));
    assert.equal(result.created,1,'Moving the display region must retain the renderer');
    console.log('PASS',JSON.stringify(result));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
