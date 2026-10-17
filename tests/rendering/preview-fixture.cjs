module.exports = Array.from({length:5000}, (_, i) => {
  const at = 128000 + (i % 1000) * 100;
  const body = i % 50 === 0 ? '　■ ■　\n　 ■　\n　■ ■　' : `Sample comment ${i}`;
  const raw = {id:`sample-${i}`,no:i+1,vposMs:at,body,commands:i%50===0?['ue','big']:['naka'],userId:`sample-user-${i%20}`,isPremium:false,score:0,postedAt:new Date(Date.UTC(2026,0,1)-i*1000).toISOString(),nicoruCount:0,nicoruId:null,isMyPost:false,source:""};
  return {at,body,raw,fork:'main'};
});
