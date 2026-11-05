/* One deliberate tap obtains a fresh local fix and sends that snapshot once. */
window.HanoiQuickShare = (() => {
  'use strict';
  let api, phase='idle', generation=0, expectedId=null, disposed=false, deferred=null;
  const node=id=>document.getElementById(id);
  function refresh(){
    if(!api || disposed)return;
    const busy=phase!=='idle';
    if(window.HanoiMapUi)window.HanoiMapUi.set('action',busy?(phase==='locating'?'Đang lấy vị trí…':'Đang gửi vị trí…'):'','active');
    node('btnSendLocation').disabled=busy || !api.isReady() || !api.isVisible() || api.liveActive();
    node('btnSendLocation').textContent=phase==='locating'?'Đang lấy vị trí…':phase==='sending'?'Đang gửi…':'Gửi vị trí';
    node('btnSendLocation').setAttribute('aria-busy',String(busy));
    node('btnStartLive').disabled=busy || !api.isReady() || !api.isVisible();
  }
  function finish(){phase='idle';expectedId=null;refresh();}
  function send(){
    if(disposed || !api || phase!=='idle' || !api.isVisible() || !api.isReady() || api.liveActive())return;
    const token=++generation;phase='locating';expectedId=null;refresh();
    api.locate(result=>{
      if(disposed || token!==generation || phase!=='locating')return;
      if(!result || !result.ok){finish();return;}
      // Render the local fix first; do not allow its ready text to overwrite the delivery result.
      deferred=setTimeout(()=>{
        deferred=null;
        if(disposed || token!==generation || phase!=='locating' || !api.isVisible() || document.hidden || api.liveActive()){finish();return;}
        phase='sending';refresh();
        const id=api.sendFixed();
        if(!id && phase==='sending'){
          finish();if(window.HanoiMapUi)window.HanoiMapUi.set('share','Chưa gửi được. Bấm Gửi vị trí để thử lại.','warning');
        }
      },0);
    });
  }
  function onDelivery(state){
    if(disposed || !api || phase!=='sending' || !state)return;
    if(state.sending && state.id)expectedId=state.id;
    if(expectedId && state.id===expectedId && !state.sending && ['received','failed'].includes(state.state))finish();
  }
  function cancel(){generation++;clearTimeout(deferred);deferred=null;finish();}
  function bind(options){api=options;node('btnSendLocation').onclick=send;refresh();}
  function destroy(){cancel();disposed=true;}
  return {bind,send,onDelivery,refresh,cancel,destroy,isBusy:()=>phase!=='idle',canStartLive:()=>!!api && phase==='idle' && api.isReady() && api.isVisible()};
})();
