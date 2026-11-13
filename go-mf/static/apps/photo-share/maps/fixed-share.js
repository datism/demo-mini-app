/* Explicit, in-call fixed-location messages. No GPS access, persistence or routing here. */
window.HanoiFixedShare = (() => {
  'use strict';
  const TYPE = 'location.fixed', ACK = TYPE + '.ack', SOURCE = 'peer-fixed-location';
  const session = 's_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2);
  const MAX_ATTEMPTS = 6, RETRY_MS = 2000, MAX_AGE_MS = 30000;
  let api, draft = null, peer = null, fixedPeer = null, outgoing = null, last = null;
  let retryTimer = null, expiryTimer = null, sequence = 0, disposed = false, rendered = null, focusPending = false;
  let peerSession = null, peerSequence = 0;
  // A retired page session must not replace a newer page's snapshot on a delayed retry.
  const retiredSessions = new Set();
  const node = id => document.getElementById(id);
  const monotonic = () => performance.now();
  function status(text, compact = text, level = 'normal') {
    node('fixedShareStatus').textContent = text;
    if (window.HanoiMapUi) window.HanoiMapUi.set('share', compact, level);
  }
  function fresh() { return draft && draft.ageMs + Math.max(0, monotonic() - draft.receivedAt) <= MAX_AGE_MS; }
  function updateButton() {
    if (api && api.onDelivery) api.onDelivery({ sending: !!outgoing, id: last && last.packet.id, state: last ? last.state : 'idle' });
    if (api && api.quickAction) return;
    const button = node('btnSendLocation');
    button.hidden = !draft;
    button.disabled = !fresh() || !!outgoing || !api.isVisible();
    button.textContent = outgoing ? 'Đang gửi…' : !fresh() ? 'Cần vị trí mới' : last && last.state === 'failed' && last.draft === draft ? 'Gửi lại' : 'Gửi vị trí';
  }
  function setLocalFix(fix) {
    clearTimeout(expiryTimer); expiryTimer = null;
    draft = fix ? { ...fix, receivedAt: monotonic() } : null;
    updateButton();
    if (draft) {
      // Disable stale snapshots without using either phone's wall clock.
      expiryTimer = setTimeout(() => { expiryTimer = null; updateButton(); if (window.HanoiMapUi && !api.quickAction && !outgoing && (!last || last.state !== 'received' || last.draft !== draft)) window.HanoiMapUi.set('share', 'Lấy vị trí mới để gửi', 'warning'); }, Math.max(1, MAX_AGE_MS - draft.ageMs + 1));
    }
  }
  function stopRetry() { clearTimeout(retryTimer); retryTimer = null; }
  function sendPacket(packet) { try { api.send(packet); } catch (_) { /* Deadline reports lack of ACK, never success. */ } }
  function attempt() {
    if (!outgoing || disposed) return;
    const sending = outgoing;
    if (sending.attempt >= MAX_ATTEMPTS) {
      outgoing = null; last.state = 'failed'; retryTimer = null;
      status('Chưa nhận được xác nhận. Máy kia có thể đã nhận vị trí; bạn có thể thử gửi lại.', 'Chưa có xác nhận · Có thể gửi lại', 'warning');
      updateButton(); return;
    }
    sending.attempt++;
    status('Đang gửi vị trí… (' + sending.attempt + '/' + MAX_ATTEMPTS + ')', 'Đang gửi vị trí…');
    sendPacket(sending.packet);
    // send() can synchronously deliver an ACK in a test/bridge; do not resurrect its timer.
    if (outgoing === sending) retryTimer = setTimeout(attempt, RETRY_MS);
  }
  function send() {
    if (disposed || outgoing || !api.isVisible()) return false;
    if (api.active()) { api.toast('Hãy kết thúc chia sẻ ảnh/video/màn hình trước.'); return false; }
    if (!fresh()) { status('Vị trí đã quá 30 giây hoặc chưa có. Bấm Vị trí của tôi để lấy lại trước khi gửi.', 'Lấy vị trí mới để gửi', 'warning'); updateButton(); return false; }
    stopRetry();
    const sameRetry = last && last.state === 'failed' && last.draft === draft;
    const packet = sameRetry ? last.packet : {
      t: TYPE, version: 1, session, seq: ++sequence,
      id: session + '_' + sequence.toString(36),
      fix: { latitude: draft.latitude, longitude: draft.longitude, accuracy: draft.accuracy,
        timestamp: draft.timestamp, approximate: draft.approximate === true,
        ageAtSendMs: draft.ageMs + Math.max(0, monotonic() - draft.receivedAt) }
    };
    last = { packet, draft, state: 'sending' };
    outgoing = { packet, attempt: 0 };
    updateButton(); attempt();
    return packet.id;
  }
  function validEnvelope(m) {
    return m && m.version === 1 && typeof m.id === 'string' && /^[A-Za-z0-9_-]{1,120}$/.test(m.id) &&
      typeof m.session === 'string' && /^[A-Za-z0-9_-]{1,90}$/.test(m.session) &&
      Number.isSafeInteger(m.seq) && m.seq > 0;
  }
  function validFix(f) {
    return f && [f.latitude, f.longitude, f.accuracy, f.timestamp, f.ageAtSendMs].every(Number.isFinite) &&
      f.latitude >= -90 && f.latitude <= 90 && f.longitude >= -180 && f.longitude <= 180 &&
      f.accuracy > 0 && f.accuracy <= 20000000 && f.timestamp > 0 && f.timestamp <= 8640000000000000 &&
      f.ageAtSendMs >= 0 && f.ageAtSendMs <= MAX_AGE_MS && typeof f.approximate === 'boolean';
  }
  function ack(m, result) { sendPacket({ t: ACK, version: 1, session: m.session, seq: m.seq, id: m.id, status: result }); }
  function receive(m) {
    if (!m || (m.t !== TYPE && m.t !== ACK)) return false;
    if (disposed || !validEnvelope(m)) return true;
    if (m.t === ACK) {
      // A late ACK after a timeout is still useful, unless superseded by another send.
      if (!last || m.session !== session || m.id !== last.packet.id || m.seq !== last.packet.seq ||
          !['received','busy','invalid'].includes(m.status)) return true;
      if (last.state === 'received') return true;
      stopRetry(); outgoing = null;
      last.state = m.status === 'received' ? 'received' : 'failed';
      if (!api.quickAction || draft === last.draft) status(m.status === 'received' ? 'Máy kia đã nhận vị trí cố định. Vị trí không tự cập nhật.' :
        m.status === 'busy' ? 'Máy kia đang chia sẻ nội dung khác. Hãy thử lại sau.' : 'Máy kia không chấp nhận dữ liệu vị trí. Hãy lấy lại vị trí và gửi lại.',
        m.status === 'received' ? 'Đã gửi vị trí' : m.status === 'busy' ? 'Máy kia đang bận · Thử lại sau' : 'Chưa gửi được · Lấy lại vị trí', m.status === 'received' ? 'success' : 'warning');
      updateButton(); return true;
    }
    if (!validFix(m.fix)) { ack(m, 'invalid'); return true; }
    if (m.session === session) return true;
    if (retiredSessions.has(m.session) || (m.session === peerSession && m.seq < peerSequence)) {
      ack(m, 'received'); return true;
    }
    if (m.session === peerSession && m.seq === peerSequence) {
      // Only the exact same operation is a duplicate. Conflicting IDs cannot replace it.
      ack(m, fixedPeer && fixedPeer.id === m.id ? 'received' : 'invalid'); return true;
    }
    if (api.active()) { ack(m, 'busy'); return true; }
    if (peerSession && peerSession !== m.session) {
      // Bound memory; refuse more new page sessions instead of forgetting retired sessions.
      if (retiredSessions.size >= 64) { ack(m, 'invalid'); return true; }
      retiredSessions.add(peerSession);
    }
    if (api.onFixedPeer) api.onFixedPeer();
    peerSession = m.session; peerSequence = m.seq;
    peer = { id: m.id, fix: { latitude: m.fix.latitude, longitude: m.fix.longitude,
      accuracy: m.fix.accuracy, timestamp: m.fix.timestamp, approximate: m.fix.approximate } };
    fixedPeer = peer;
    rendered = null; focusPending = true;
    updatePeerCard();
    // ACK means validated and retained in this page, not that the user viewed the map.
    ack(m, 'received');
    if (!document.hidden) { api.openMap(); renderPeer(); }
    return true;
  }
  function covered(fix) {
    const config = api.getConfig();
    if (!config) return null;
    const b = config.bounds;
    return fix.longitude >= b[0][0] && fix.longitude <= b[1][0] && fix.latitude >= b[0][1] && fix.latitude <= b[1][1];
  }
  function updatePeerCard() {
    const card = node('peerLocationCard'); card.hidden = !peer;
    if (window.HanoiMapUi) window.HanoiMapUi.peer(peer && peer.fix, peer ? covered(peer.fix) : null, peer && peer.liveState);
    if (!peer) return;
    const fix = peer.fix;
    const time = new Date(fix.timestamp).toLocaleString('vi-VN');
    node('peerLocationInfo').textContent = 'Vị trí đối phương' + (peer.liveState ? ' · ' + peer.liveState : '') + (fix.approximate ? ' (gần đúng)' : '') +
      ' · Sai số khoảng ' + Math.ceil(fix.accuracy) + ' m · Đo lúc ' + time +
      (peer.liveState ? '' : ' · Không tự cập nhật') + (covered(fix) === false ? ' · Ngoài vùng bản đồ Hà Nội đã tải' : '');
    const focusButton = node('btnPeerLocation') || node('peerCompactRow');
    if (focusButton) focusButton.disabled = covered(fix) !== true;
    node('peerLocationCoordinates').textContent = fix.latitude.toFixed(6) + ', ' + fix.longitude.toFixed(6);
  }
  function clearPeerLayers() {
    const map = api && api.getMap(); if (!map) return;
    for (const layer of ['peer-fixed-dot','peer-fixed-accuracy']) if (map.getLayer(layer)) map.removeLayer(layer);
    if (map.getSource(SOURCE)) map.removeSource(SOURCE);
    rendered = null;
  }
  function renderPeer() {
    if (disposed || !peer || !api.isVisible()) return;
    const map = api.getMap();
    if (!map || !(map.hanoiLoaded || map.isStyleLoaded())) return;
    updatePeerCard();
    if (!covered(peer.fix)) { clearPeerLayers(); focusPending = false; return; }
    if (rendered !== peer.id || !map.getSource(SOURCE)) {
      clearPeerLayers();
      const f = peer.fix;
      map.addSource(SOURCE, { type: 'geojson', data: { type: 'FeatureCollection', features: [
        { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [window.HanoiMyLocation.circle(f.longitude, f.latitude, f.accuracy)] } },
        { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [f.longitude, f.latitude] } }
      ] } });
      map.addLayer({ id: 'peer-fixed-accuracy', type: 'fill', source: SOURCE, filter: ['==','$type','Polygon'],
        paint: { 'fill-color': '#df6817', 'fill-opacity': 0.17 } });
      map.addLayer({ id: 'peer-fixed-dot', type: 'circle', source: SOURCE, filter: ['==','$type','Point'],
        paint: { 'circle-color': '#df6817', 'circle-radius': 9, 'circle-stroke-width': 3, 'circle-stroke-color': '#fff' } });
      rendered = peer.id;
    }
    if (focusPending) { focusPending = false; focusPeer(); }
  }
  function focusPeer() {
    if (disposed || !peer || !covered(peer.fix) || !api.isVisible()) return;
    const map = api.getMap(); if (!map || !(map.hanoiLoaded || map.isStyleLoaded())) return;
    const fix = peer.fix, config = api.getConfig();
    map.easeTo({ center: [fix.longitude,fix.latitude], zoom: Math.min(fix.accuracy > 1000 ? 12 : fix.accuracy > 200 ? 14 : 16, config.maxZoom), duration: 500 });
  }
  function onMapReady() { updateButton(); updatePeerCard(); renderPeer(); }
  function onClose() {
    stopRetry(); outgoing = null;
    if (last && last.state === 'sending') {
      last.state = 'failed'; status('Đã dừng chờ xác nhận khi đóng bản đồ. Máy kia có thể đã nhận vị trí.', 'Đã dừng chờ xác nhận', 'warning');
    }
    setLocalFix(null);
  }
  function destroy() {
    onClose(); clearPeerLayers(); peer = null; fixedPeer = null; last = null; retiredSessions.clear();
    peerSession = null; peerSequence = 0; disposed = true;
    node('peerLocationCard').hidden = true; if (window.HanoiMapUi) window.HanoiMapUi.peer(null, null); status('');
  }
  function bind(options) {
    api = options;
    if (!options.quickAction) node('btnSendLocation').onclick = send;
    const focusButton = node('btnPeerLocation') || node('peerCompactRow');
    if (focusButton) focusButton.onclick = focusPeer;
    updateButton();
  }
  function presentLive(value, focus) {
    if (disposed || !value || !value.fix) return;
    if (!peer || peer.id !== value.id) rendered = null;
    peer = value; focusPending = !!focus;
    updatePeerCard(); renderPeer();
  }
  return { bind, setLocalFix, send, receive, onMapReady, onClose, destroy, presentLive };
})();
