const assert = require('node:assert/strict');
const fs = require('node:fs');
const zlib = require('node:zlib');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require('playwright');
(async () => {
  const rows = require('./preview-fixture.cjs');
  const art = rows.filter(c => /[\r\n]/.test(c.body) || c.raw.commands.some(m => /^(full|ender|ca|patissier)$/.test(m)));
  assert.ok(art.length > 10);
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const page = await browser.newPage({viewport:{width:900,height:2000}});
    await page.goto(pathToFileURL(path.resolve('app/src/main/assets/overlay.html')).href);
    const result = await page.evaluate(async comments => {
      const options = {nativeV1:true,epoch:'art-test',size:14,opacity:.75,speed:.7,fps:60,density:1,position:'center',colorStyle:'original',fontStyle:'default',renderMode:'balanced'};
      window.commentOverlay.sync(140000,0,true);
      await window.commentOverlay.load(comments.map(c=>({...c,raw:JSON.stringify(c.raw)})),options);
      const baselineCanvas = document.querySelector('#comments').cloneNode(false);
      const groups = new Map();
      for (const c of comments) {
        if (!groups.has(c.fork)) groups.set(c.fork,{id:c.fork,fork:c.fork,comments:[]});
        groups.get(c.fork).comments.push({...c.raw,body:c.body,vposMs:c.at});
      }
      const stock = new NiconiComments(baselineCanvas,[...groups.values()],{format:'v1',mode:'default',keepCA:true,lazy:true,scale:14/24,
        config:{contextStrokeOpacity:.62,contextFillLiveOpacity:1,commentDrawRange:1920,commentDrawPadding:0,collisionRange:{left:0,right:1920},fpsInterval:1000/60}});
      const frames = [];
      for (const time of [133000,140000,160000,190000,215000]) {
        window.commentOverlay.drawAt(time); stock.drawCanvas(time/10,true);
        frames.push({time,equal:document.querySelector('#comments').toDataURL()===baselineCanvas.toDataURL()});
      }
      stock.destroy();
      return {comments:comments.length,frames};
    },art);
    console.log(JSON.stringify(result));
    assert.ok(result.frames.every(f=>f.equal),'Preview AA pixels differ from unmodified OSS v1 rendering');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
