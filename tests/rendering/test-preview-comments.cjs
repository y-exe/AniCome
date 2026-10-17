const assert = require('node:assert/strict');
const fs = require('node:fs');
const zlib = require('node:zlib');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require('playwright');
(async () => {
  const rows = require('./preview-fixture.cjs');
  const chosen = rows.slice(0, 5000).map(r => ({...r, raw: JSON.stringify(r.raw)}));
  chosen.push({at:133000, body:'FIXED_TOP', raw:JSON.stringify({commands:['ue','big'],postedAt:'2026-01-01T00:00:00Z'})});
  chosen.push({at:133000, body:'FIXED_BOTTOM', raw:JSON.stringify({commands:['shita'],postedAt:'2026-01-01T00:00:00Z'})});
  chosen.push({at:133000, body:'SCROLL', raw:JSON.stringify({commands:['naka'],postedAt:'2026-01-01T00:00:00Z'})});
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const page = await browser.newPage({viewport:{width:900,height:2000}});
    await page.goto(pathToFileURL(path.resolve('app/src/main/assets/overlay.html')).href);
    const result = await page.evaluate(async comments => {
      const Real = window.NiconiComments;
      let instance;
      window.NiconiComments = class extends Real { constructor(...args){super(...args);instance=this;} };
      window.commentOverlay.sync(133000,0,true);
      await window.commentOverlay.load(comments,{nativeV1:true,epoch:'preview',size:22,opacity:.4,speed:1.2,fps:120,density:1,position:'custom',customY:.23,fontStyle:'default',colorStyle:'original',renderMode:'quality'});
      const fixed = instance.comments.filter(c => c.comment.mail.includes('ue') || c.comment.mail.includes('shita'));
      const moved = fixed.filter(c => Real.internal.utils.getPosX(c.comment,13300,instance.ctx.config,false) !== Real.internal.utils.getPosX(c.comment,13400,instance.ctx.config,false));
      const canvas = document.querySelector('#comments');
      const diagnostics = {fixed:fixed.length,moved:moved.length,scale:instance.ctx.options.scale,opacity:canvas.style.opacity,top:canvas.style.top};
      const probes = ['FIXED_TOP','FIXED_BOTTOM','SCROLL'].map(body => {
        const c=instance.comments.find(c=>c.content===body);
        return {body,loc:c.loc,mail:c.comment.mail,x1:Real.internal.utils.getPosX(c.comment,13300,instance.ctx.config,false),x2:Real.internal.utils.getPosX(c.comment,13400,instance.ctx.config,false)};
      });
      return {probes,diagnostics};
    },chosen);
    console.log(JSON.stringify(result));
    assert.equal(result.diagnostics.moved,0); assert.ok(result.diagnostics.fixed>100); assert.equal(result.diagnostics.scale,22/24); assert.equal(result.diagnostics.opacity,'0.4'); assert.equal(result.diagnostics.top,'23%');
    const probes = result.probes;
    assert.equal(probes[0].loc,'ue'); assert.equal(probes[1].loc,'shita'); assert.equal(probes[2].loc,'naka');
    assert.equal(probes[0].x1,probes[0].x2); assert.equal(probes[1].x1,probes[1].x2); assert.notEqual(probes[2].x1,probes[2].x2);
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
