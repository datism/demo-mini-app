/* Foreground live sharing over the current ADC. GPS starts only after a local tap + peer ACK. */
window.HanoiLiveShare = (() => {
  'use strict';
  const TYPE = 'location.live', ACK = TYPE + '.ack';
  const MODES = [0,900000,3600000], RETRY = 2000, MAX_ATTEMPTS = 6, STALE = 20000;
  let localButtonLocked = false;
  let api, own = null, peer = null, tickTimer = null, disposed = false, peerPresentation = null;
  const transactions = new Map(), retired = new Set();
  const node = id => document.getElementById(id), now = () => performance.now();
  const reasonText = {
    user:'Đã dừng chia sẻ', closed:'Đã dừng khi rời bản đồ', expired:'Đã hết thời gian chia sẻ',
    navigation:'Đã dừng khi đổi ứng dụng', permission_denied:'Chưa có quyền vị trí',
    permission_timeout:'Hết thời gian chờ cấp quyền', permission_pending:'Hãy xử lý hộp thoại quyền vị trí',
    unavailable:'Chưa mở được nguồn vị trí', native_timeout:'Không nhận được phản hồi Android',
    peer_busy:'Máy kia đang bận', peer_unavailable:'Chưa xác nhận được máy kia',
    replaced:'Đã chuyển sang vị trí cố định'
  };
  function bridge() { return window.AndroidBridge; }
  function call(name,value) { const b=bridge(); if (!b || typeof b[name] !== 'function') throw new Error('Cần APK hỗ trợ chia sẻ trực tiếp'); b[name](value); }
  function transmit(message) { try { api.send(message); } catch (_) {} }
  function remaining(session) { return session.duration === 0 ? null : Math.max(0,Math.ceil(session.endAt-now())); }
  function packet(session,kind,reason) {
    const m={t:TYPE,version:1,id:session.id,seq:++session.seq,kind,durationMs:session.duration,remainingMs:remaining(session)};
    if(reason)m.reason=reason;
    return m;
  }
  function sendControl(session,kind,reason) {
    // Superseded pause/resume/start retries must not outlive the current control state.
    for(const [key,tx] of transactions) if(tx.session.id===session.id) transactions.delete(key);
    const message=packet(session,kind,reason),key=message.id+':'+message.seq;
    const tx={session,message,attempt:0,due:now()}; transactions.set(key,tx); retryControl(tx);
  }
  function retryControl(tx) {
    const key=tx.message.id+':'+tx.message.seq;
    if(!transactions.has(key))return;
    if(tx.attempt>=MAX_ATTEMPTS){
      transactions.delete(key);
      if(tx.message.kind==='start' && own && own.id===tx.message.id) stop('peer_unavailable');
      else if(tx.message.kind==='stop' && !own) notice('Đã dừng trên máy này · Chưa có xác nhận máy kia','warning');
      return;
    }
    tx.attempt++;tx.due=now()+RETRY;
    // Recalculate lease on retry, without changing ID/sequence/control intent.
    tx.message.remainingMs=remaining(tx.session);transmit({...tx.message});
  }
  function notice(text,level='normal') {
    node('liveShareDetail').textContent=text;
    if(window.HanoiMapUi)window.HanoiMapUi.set('live',text,level);
  }
  function validEnvelope(m) {
    return m && m.version===1 && typeof m.id==='string' && /^[A-Za-z0-9_-]{1,100}$/.test(m.id) &&
      Number.isSafeInteger(m.seq) && m.seq>0;
  }
  function validLease(m) {
    return MODES.includes(m.durationMs) && (m.durationMs===0 ? m.remainingMs===null :
      Number.isFinite(m.remainingMs) && m.remainingMs>=0 && m.remainingMs<=m.durationMs);
  }
  function validFix(f) {
    return f && [f.latitude,f.longitude,f.accuracy,f.timestamp,f.ageAtSendMs].every(Number.isFinite) &&
      f.latitude>=-90 && f.latitude<=90 && f.longitude>=-180 && f.longitude<=180 &&
      f.accuracy>0 && f.accuracy<=20000000 && f.timestamp>0 && f.timestamp<=8640000000000000 &&
      f.ageAtSendMs>=0 && f.ageAtSendMs<=30000 && typeof f.approximate==='boolean';
  }
  function remember(id) { if(retired.has(id))return true; if(retired.size>=128)return false; retired.add(id);return true; }
  function ack(m,status) {transmit({t:ACK,version:1,id:m.id,seq:m.seq,kind:m.kind,status});}
  function start() {
    if(disposed || own || !api.isVisible() || document.hidden || api.canStart && !api.canStart())return;
    if(api.active()){api.toast('Hãy kết thúc chia sẻ nội dung trước.');return;}
    if(transactions.size){notice('Đang xác nhận kết thúc phiên trước · Thử lại sau','warning');return;}
    const b=bridge();
    if(!b || !['startLiveMapLocation','stopLiveMapLocation','pauseLiveMapLocation','resumeLiveMapLocation'].every(k=>typeof b[k]==='function')){
      notice('Cần cài APK hỗ trợ chia sẻ trực tiếp','warning');return;
    }
    const duration=0;
    api.cancelOneShot();
    own={id:'live_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2),duration,
      endAt:duration===0?0:now()+duration,seq:0,nativeSeq:0,phase:'connecting',nativeStarted:false,
      nativeDeadline:0,lastHeartbeat:now(),lastFixAt:null};
    sendControl(own,'start');renderUi();
  }
  function startNative(session) {
    if(!own || own!==session || session.nativeStarted)return;
    const left=remaining(session);if(left!==null && left<=0){stop('expired');return;}
    if(document.hidden){stop('closed');return;}
    session.nativeStarted=true;session.phase='waiting';session.nativeDeadline=now()+10000;
    try{call('startLiveMapLocation',JSON.stringify({id:session.id,durationMs:left===null?0:left}));}
    catch(_){stop('unavailable');}
    renderUi();
  }
  function stop(reason='user',notify=true) {
    if(!own)return;
    const previous=own;own=null;
    try{call('stopLiveMapLocation',previous.id);}catch(_){}
    for(const [key,tx] of transactions)if(tx.session.id===previous.id)transactions.delete(key);
    if(notify)sendControl(previous,'stop',reason);
    notice(reasonText[reason] || 'Đã dừng chia sẻ',reason==='user'||reason==='expired'||reason==='closed'?'normal':'warning');
    renderUi();
  }
  function requestPause() {
    if(!own || !own.nativeStarted || own.phase==='paused')return;
    own.phase='paused';own.nativeDeadline=0;
    try{call('pauseLiveMapLocation',own.id);}catch(_){}
    sendControl(own,'pause');renderUi();
  }
  function onNativeResult(m) {
    if(disposed || !own || !m || m.id!==own.id || !Number.isSafeInteger(m.seq) || m.seq<=own.nativeSeq)return;
    own.nativeSeq=m.seq;
    if(m.event==='stopped'){stop(reasonText[m.reason]?m.reason:'unavailable');return;}
    if(m.event==='permission'){own.phase='permission';own.nativeDeadline=now()+100000;renderUi();return;}
    if(m.event==='paused'){if(own.phase!=='paused'){own.phase='paused';sendControl(own,'pause');}own.nativeDeadline=0;renderUi();return;}
    if(m.event==='active'||m.event==='waiting'){
      if(document.hidden){requestPause();return;}
      const wasPaused=own.phase==='paused';own.phase='waiting';own.nativeDeadline=0;
      if(wasPaused)sendControl(own,'resume');renderUi();return;
    }
    if(m.event!=='fix' || document.hidden || own.phase==='paused')return;
    const f=m.fix,fix=f&&{latitude:f.latitude,longitude:f.longitude,accuracy:f.accuracy,timestamp:f.timestamp,
      approximate:f.approximate,ageAtSendMs:f.ageMs};
    if(!validFix(fix))return;
    if(remaining(own)!==null && remaining(own)<=0){stop('expired');return;}
    own.phase='active';own.nativeDeadline=0;own.lastFixAt=now();
    const message=packet(own,'update');message.fix=fix;
    transmit(message);
    api.showLocalFix({...fix,ageMs:fix.ageAtSendMs});
    renderUi();
  }
  function receive(m) {
    if(!m || (m.t!==TYPE && m.t!==ACK))return false;
    if(disposed || !validEnvelope(m))return true;
    if(m.t===ACK){
      const key=m.id+':'+m.seq,tx=transactions.get(key);
      if(!tx || tx.message.kind!==m.kind || !['accepted','stopped','busy','invalid'].includes(m.status))return true;
      transactions.delete(key);
      if(m.kind==='start' && own && own.id===m.id){
        if(m.status==='accepted')startNative(own);
        else stop(m.status==='busy'?'peer_busy':'peer_unavailable',false);
      }else if(m.kind==='stop' && !own && m.status==='stopped')notice('Đã dừng chia sẻ trực tiếp');
      return true;
    }
    if(!['start','update','pause','resume','heartbeat','stop'].includes(m.kind) || !validLease(m))return true;
    if(retired.has(m.id)){if(m.kind!=='update'&&m.kind!=='heartbeat')ack(m,'stopped');return true;}
    if(m.kind==='stop'){
      if(!remember(m.id)){ack(m,'invalid');return true;}
      if(peer && peer.id===m.id){peer.phase='ended';peer.seq=Math.max(peer.seq,m.seq);peer.reason=m.reason==='expired'?'expired':'stopped';publishPeer();renderUi();}
      ack(m,'stopped');return true;
    }
    if(m.kind==='start'){
      if(api.active()){ack(m,'busy');return true;}
      if(m.remainingMs!==null && m.remainingMs<=0){remember(m.id);ack(m,'stopped');return true;}
      if(peer && peer.id===m.id){shrinkLease(m);ack(m,'accepted');return true;}
      if(peer && !remember(peer.id)){ack(m,'invalid');return true;}
      if(retired.size>=128){ack(m,'invalid');return true;}
      peer={id:m.id,seq:m.seq,duration:m.durationMs,endAt:m.remainingMs===null?0:now()+m.remainingMs,
        phase:'waiting',fix:null,fixSeq:0,fixAt:null,fixAge:0,lastMessageAt:now(),reason:null};
      peerPresentation=null;ack(m,'accepted');renderUi();
      if(!document.hidden)api.openMap();return true;
    }
    if(!peer || peer.id!==m.id || peer.phase==='ended' || m.durationMs!==peer.duration)return true;
    if(m.seq<=peer.seq){if(m.kind==='pause'||m.kind==='resume')ack(m,'accepted');return true;}
    if(m.kind==='update'&&!validFix(m.fix))return true;
    peer.seq=m.seq;peer.lastMessageAt=now();shrinkLease(m);
    if(peer.endAt>0 && now()>=peer.endAt){endPeer('expired');return true;}
    if(m.kind==='update'){
      peer.fix={latitude:m.fix.latitude,longitude:m.fix.longitude,accuracy:m.fix.accuracy,timestamp:m.fix.timestamp,approximate:m.fix.approximate};
      peer.fixSeq=m.seq;peer.fixAt=now();peer.fixAge=m.fix.ageAtSendMs;peer.phase='active';peer.needFresh=false;
    }else if(m.kind==='pause')peer.phase='paused';
    else if(m.kind==='resume')peer.phase='waiting';
    // Heartbeat carries no position and never makes an old fix fresh.
    publishPeer();renderUi();
    if(m.kind==='pause'||m.kind==='resume')ack(m,'accepted');return true;
  }
  function shrinkLease(m){if(peer && peer.id===m.id && m.remainingMs!==null)peer.endAt=Math.min(peer.endAt,now()+m.remainingMs);}
  function endPeer(reason){if(!peer)return;remember(peer.id);peer.phase='ended';peer.reason=reason;publishPeer();renderUi();}
  function peerState(){
    if(!peer)return null;
    if(peer.phase==='ended')return peer.reason==='expired'?'Hết thời gian':'Đã dừng';
    if(peer.phase==='paused')return 'Tạm dừng';
    if(!peer.fix || peer.phase==='waiting')return 'Chờ vị trí mới';
    if(peer.needFresh)return 'Chưa có vị trí mới';
    if(now()-peer.lastMessageAt>STALE || now()-peer.fixAt+peer.fixAge>STALE)return 'Chưa có vị trí mới';
    return 'Trực tiếp';
  }
  function publishPeer(){
    if(!peer || !peer.fix)return;
    const state=peerState(),key=peer.id+':'+peer.fixSeq+':'+state;
    if(key===peerPresentation)return;
    const first=peerPresentation===null;peerPresentation=key;
    api.presentPeer({id:'live_'+peer.id+'_'+peer.fixSeq,fix:peer.fix,liveState:state},first);
  }
  function forgetPeer(){if(peer)remember(peer.id);peer=null;peerPresentation=null;renderUi();}
  function formatRemaining(session){
    const left=remaining(session);if(left===null)return 'Đến hết cuộc gọi';
    const seconds=Math.ceil(left/1000);return Math.floor(seconds/60)+':'+String(seconds%60).padStart(2,'0');
  }
  function renderUi(){
    node('liveLauncher').hidden=!!own;node('liveOwnRow').hidden=!own;
    const legacyLocationButton=node('btnMyLocation');
    if(own && legacyLocationButton){legacyLocationButton.disabled=true;localButtonLocked=true;}
    else if(localButtonLocked && legacyLocationButton){legacyLocationButton.disabled=false;localButtonLocked=false;}
    if(node('liveSharingHint'))node('liveSharingHint').hidden=!own;
    if(window.HanoiMapUi && window.HanoiMapUi.ownLive)window.HanoiMapUi.ownLive(own ? own.phase==='paused'?'paused':'active' : 'stopped');
    if(own){
      const label=own.phase==='connecting'?'Đang kết nối':own.phase==='permission'?'Chờ cấp quyền':own.phase==='paused'?'Tạm dừng':
        own.lastFixAt===null?'Đang chờ GPS':now()-own.lastFixAt>STALE?'Chưa có vị trí mới':'Đang chia sẻ';
      node('liveOwnStatus').textContent='Trực tiếp · '+formatRemaining(own);
      if(window.HanoiMapUi)window.HanoiMapUi.set('live',label==='Đang chia sẻ'?'Đang chia sẻ trực tiếp':label,'active');
    }
    node('peerLiveState').hidden=!peer || !!peer.fix;
    if(peer)node('peerLiveState').textContent='Đối phương · '+peerState();
    if(window.HanoiQuickShare)window.HanoiQuickShare.refresh();
  }
  function tick(){
    if(disposed)return;
    if(own){
      const left=remaining(own);
      if(left!==null && left<=0)stop('expired');
      else if(own.nativeDeadline>0 && now()>=own.nativeDeadline)stop('native_timeout');
      else if(own.nativeStarted && own.phase!=='paused' && !document.hidden && now()-own.lastHeartbeat>=10000){
        own.lastHeartbeat=now();transmit(packet(own,'heartbeat'));
      }
    }
    if(peer && peer.phase!=='ended' && peer.endAt>0 && now()>=peer.endAt)endPeer('expired');
    for(const tx of [...transactions.values()])if(now()>=tx.due)retryControl(tx);
    publishPeer();renderUi();tickTimer=setTimeout(tick,1000);
  }
  function onVisibility(){
    if(peer && peer.phase!=='ended'){peer.needFresh=true;publishPeer();renderUi();}
    if(!own)return;
    if(document.hidden)requestPause();
    else if(own.nativeStarted){try{call('resumeLiveMapLocation',own.id);}catch(_){stop('unavailable');}}
  }
  function onClose(){stop('closed');}
  function onMapReady(){publishPeer();renderUi();}
  function destroy(){
    if(disposed)return;stop('closed');disposed=true;clearTimeout(tickTimer);tickTimer=null;transactions.clear();
    peer=null;peerPresentation=null;retired.clear();
    document.removeEventListener('visibilitychange',onVisibility);window.removeEventListener('pagehide',onClose);
    node('liveOwnRow').hidden=true;node('peerLiveState').hidden=true;
  }
  function bind(options){
    api=options;node('btnStartLive').onclick=start;node('btnStopLive').onclick=()=>stop('user');
    document.addEventListener('visibilitychange',onVisibility);window.addEventListener('pagehide',onClose);
    renderUi();tickTimer=setTimeout(tick,1000);
  }
  return {bind,start,stop,receive,onNativeResult,onClose,onMapReady,destroy,forgetPeer,isActive:()=>!!own};
})();
