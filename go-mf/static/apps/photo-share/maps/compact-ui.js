/* A quiet, single-card interface. Position rows are also the map focus controls. */
window.HanoiMapUi = (() => {
  'use strict';
  let serial=0,selfFix=null,selfMode='static',selfCovered=true,disposed=false;
  const entries={},timeouts=new Map(),node=id=>document.getElementById(id);
  function refresh(){
    const selected=entries.map && entries.map.level==='error'?entries.map:
      entries.live && entries.live.level==='active'?entries.live:
      entries.action && entries.action.level==='active'?entries.action:
      [entries.live,entries.local,entries.share].filter(Boolean).sort((a,b)=>b.order-a.order)[0]||entries.map;
    const target=node('mapCompactStatus'),text=selected?selected.text:'';
    if(target.textContent!==text)target.textContent=text;target.hidden=!text;target.dataset.level=selected?selected.level:'normal';
  }
  function set(channel,text,level='normal'){
    if(disposed)return;
    if(!text && !entries[channel])return;
    if(entries[channel] && entries[channel].text===text && entries[channel].level===level)return;
    clearTimeout(timeouts.get(channel));timeouts.delete(channel);
    const entry=text?{text,level,order:++serial}:null;entries[channel]=entry;
    // A send has already been acknowledged. Keep the point/time, retire the confirmation line.
    if(channel==='share' && level==='success'){
      entries.local=null;
      timeouts.set(channel,setTimeout(()=>{timeouts.delete(channel);if(entries[channel]===entry){entries[channel]=null;refresh();}},4000));
    }
    refresh();
  }
  function accuracy(value){return value>=1000?(value/1000).toFixed(1)+' km':Math.ceil(value)+' m';}
  function time(value){return new Date(value).toLocaleTimeString('vi-VN',{hour:'2-digit',minute:'2-digit'});}
  function renderSelf(){
    const row=node('selfCompactRow');row.hidden=!selfFix;
    if(!selfFix)return;
    row.disabled=!selfCovered;
    const label=selfMode==='live'?' · Trực tiếp':selfMode==='paused'?' · Tạm dừng':selfMode==='stopped'?' · Đã dừng':'';
    const text='Tôi'+label+(selfFix.approximate?' (gần đúng)':'')+' · ±'+accuracy(selfFix.accuracy)+' · '+time(selfFix.timestamp)+(selfCovered?'':' · Ngoài vùng');
    node('selfCompactInfo').textContent=text;row.setAttribute('aria-label',text+(selfCovered?'. Xem trên bản đồ':''));
  }
  function self(fix,mode='static',covered=true){selfFix=fix;selfMode=mode;selfCovered=covered;renderSelf();}
  function ownLive(state){
    if(!selfFix || !['live','paused','stopped'].includes(selfMode))return;
    selfMode=state==='active'?'live':state==='paused'?'paused':'stopped';renderSelf();
  }
  function peer(fix,covered,liveState=null){
    const row=node('peerCompactRow');row.hidden=!fix;
    if(!fix)return;
    row.disabled=covered!==true;
    const text='Đối phương'+(liveState?' · '+liveState:'')+(fix.approximate?' (gần đúng)':'')+' · ±'+accuracy(fix.accuracy)+' · '+time(fix.timestamp)+(covered===false?' · Ngoài vùng':'');
    node('peerCompactInfo').textContent=text;row.setAttribute('aria-label',text+(covered===true?'. Xem trên bản đồ':''));
  }
  function bind(options={}){
    if(options.focusSelf)node('selfCompactRow').onclick=options.focusSelf;
    refresh();
  }
  function close(){self(null);set('local','');}
  function destroy(){
    for(const timer of timeouts.values())clearTimeout(timer);timeouts.clear();
    self(null);peer(null);for(const key of Object.keys(entries))delete entries[key];refresh();disposed=true;
  }
  return {bind,set,self,peer,ownLive,close,destroy};
})();
