const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require('playwright');
(async () => {
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const page = await browser.newPage();
    await page.goto(pathToFileURL(path.resolve('app/src/main/assets/overlay.html')).href);
    const result = await page.evaluate(() => {
      const results=[];
      for (const engine of ['2d','auto']) for (const lazy of [true,false]) for (const command of ['ue','shita','naka']) {
        const canvas=document.createElement('canvas');canvas.width=1920;canvas.height=1080;if(engine==='2d')canvas.getContext('2d');
        const comment={id:'one',no:1,vposMs:133000,body:'FIXED VISIBILITY',commands:[command],userId:'one',isPremium:true,score:0,postedAt:'2026-01-01T00:00:00Z',nicoruCount:0,nicoruId:null,source:'trunk',isMyPost:false};
        const renderer=new NiconiComments(canvas,[{id:'one',fork:'main',comments:[comment]}],{format:'v1',keepCA:true,lazy,scale:14/24});
        renderer.drawCanvas(13400,true);
        const capture=document.createElement('canvas');capture.width=1920;capture.height=1080;const ctx=capture.getContext('2d');ctx.drawImage(canvas,0,0);const data=ctx.getImageData(0,0,1920,1080).data;
        let pixels=0;for(let i=3;i<data.length;i+=4)if(data[i])pixels++;
        results.push({engine,renderer:renderer.renderer.rendererName,lazy,command,pixels,position:renderer.comments.map(c=>({loc:c.loc,y:c.posY,width:c.width,height:c.height,invisible:c.invisible}))});
        renderer.destroy();
      }
      return results;
    });
    fs.writeFileSync('artifacts/fixed-visible-pixels.json',JSON.stringify(result,null,2));
    console.log(JSON.stringify(result));
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
