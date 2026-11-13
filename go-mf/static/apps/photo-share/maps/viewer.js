/* Offline Hanoi map: one-tap fixed and foreground live sharing on the existing ADC. */
window.HanoiViewer = (() => {
  let api, map, config, opening, timer, pending, visible = false, disposed = false, lastError = null, mapLoaded = false;
  const seen = new Set();
  const status = (text, compact = text, level = 'normal') => {
    document.getElementById('mapStatus').textContent = text;
    if (window.HanoiMapUi) window.HanoiMapUi.set('map', compact, level);
  };
  const message = (t, id) => ({ t, version: 1, id });
  async function ensureMap() {
    if (map) { map.resize(); if (window.HanoiQuickShare) window.HanoiQuickShare.refresh(); window.HanoiFixedShare.onMapReady(); if (window.HanoiLiveShare) window.HanoiLiveShare.onMapReady(); return; }
    if (opening) return opening;
    opening = (async () => {
      if (!window.maplibregl) throw new Error('Thiếu thư viện bản đồ. Cần chạy bước prepare và cập nhật DCAR.');
      const response = await fetch('maps/config.json', { cache: 'no-store' });
      if (!response.ok) throw new Error('Thiếu gói bản đồ Hà Nội trên DCAR.');
      config = await response.json();
      const styleUrl = new URL('maps/style.json', location.href);
      const styleResponse = await fetch(styleUrl.href, { cache: 'no-store' });
      if (!styleResponse.ok) throw new Error('Không đọc được style: HTTP ' + styleResponse.status);
      const style = await styleResponse.json();
      if (!style.sources || !style.sources.hanoi) throw new Error('Style không có nguồn hanoi.');
      // Vector tile workers require absolute URLs. Preserve template braces after URL encoding.
      style.sources.hanoi.tiles = style.sources.hanoi.tiles.map(template =>
        new URL(template, styleUrl.href).href.replace(/%7B/gi, '{').replace(/%7D/gi, '}'));
      if (!style.glyphs) throw new Error('Thiếu cấu hình font offline. Hãy chạy cập nhật bản đồ chi tiết.');
      style.glyphs = new URL(style.glyphs, styleUrl.href).href
        .replace(/%7B/gi, '{').replace(/%7D/gi, '}');
      style.sources.hanoi.bounds = [config.bounds[0][0], config.bounds[0][1], config.bounds[1][0], config.bounds[1][1]];
      if (disposed) return;
      lastError = null;
      map = new maplibregl.Map({
        container: 'hanoiMap',
        style,
        center: config.center, zoom: config.zoom,
        minZoom: config.minZoom, maxZoom: config.maxZoom,
        maxBounds: config.bounds, attributionControl: true
      });
      // Pinch zoom and drag keep the map free of extra navigation buttons.
      map.on('load', () => {
        if (disposed) return;
        mapLoaded = true;
        // Once loaded, sources and layers can be added at any time; isStyleLoaded() only says nothing is parsing
        // right now, and live updates keep something parsing. The map code checks this flag first.
        map.hanoiLoaded = true;
        if (!lastError) status('Đã nạp bản đồ chi tiết · ' + config.regionName, '');
        window.HanoiFixedShare.onMapReady();
        if (window.HanoiLiveShare) window.HanoiLiveShare.onMapReady();
        if (window.HanoiQuickShare) window.HanoiQuickShare.refresh();
        if (pending) transmit();
      });
      map.on('idle', () => { if (!disposed) { window.HanoiFixedShare.onMapReady(); if (window.HanoiQuickShare) window.HanoiQuickShare.refresh(); } });
      map.on('error', event => {
        lastError = String(event.error && event.error.message || 'Lỗi không xác định');
        console.error('[HANOI_MAP]', lastError);
        status('Lỗi bản đồ: ' + lastError.slice(0, 400), 'Bản đồ chưa tải đủ', 'error');
      });
      map.on('sourcedata', event => {
        if (event.sourceId === 'hanoi' && event.sourceDataType === 'content' && event.tile && !lastError) {
          status('Bản đồ offline · Phóng to để xem tên đường · ' + config.regionName, '');
        }
      });
      map.on('webglcontextlost', () => status('Mất ngữ cảnh đồ họa. Đóng rồi mở lại bản đồ hoặc khởi động lại cuộc gọi.', 'Cần mở lại bản đồ', 'error'));
    })().catch(e => { status(e.message, 'Chưa mở được bản đồ', 'error'); }).finally(() => { opening = null; });
    return opening;
  }
  function transmit() {
    if (!pending || disposed) return;
    const request = pending;
    request.attempt++;
    api.send(message('map.open', request.id));
    if (pending !== request) return;
    if (request.attempt >= 12) {
      clearInterval(timer); timer = null;
      status('Đã gửi yêu cầu mở bản đồ; chưa nhận xác nhận từ máy kia. Có thể bấm lại để thử.', 'Chưa xác nhận mở bản đồ ở máy kia', 'warning');
      pending = null;
    }
  }
  function open(sync) {
    if (disposed) return;
    if (api.active()) { api.toast('Hãy kết thúc chia sẻ ảnh/video/màn hình trước.'); return; }
    visible = true; api.show('mapView');
    requestAnimationFrame(() => ensureMap());
    if (sync) {
      clearInterval(timer);
      pending = { id: Date.now().toString(36) + '-' + Math.random().toString(36).slice(2), attempt: 0 };
      timer = setInterval(transmit, 1500); transmit();
    }
  }
  function receive(m) {
    if (window.HanoiLiveShare && window.HanoiLiveShare.receive(m)) return true;
    if (window.HanoiFixedShare.receive(m)) return true;
    if (!m || !['map.open','map.ack','map.busy'].includes(m.t)) return false;
    if (m.version !== 1 || typeof m.id !== 'string' || m.id.length > 100) return true;
    if (m.t === 'map.open') {
      if (api.active()) { api.send(message('map.busy', m.id)); return true; }
      api.send(message('map.ack', m.id));
      if (seen.has(m.id)) return true;
      seen.add(m.id); if (seen.size > 50) seen.delete(seen.values().next().value);
      open(false);
    } else if (pending && m.id === pending.id) {
      clearInterval(timer); timer = null; pending = null;
      if (m.t === 'map.busy') status('Máy kia đang chia sẻ nội dung khác.', 'Đối phương đang bận', 'warning');
    }
    return true;
  }
  function close() { if (window.HanoiQuickShare) window.HanoiQuickShare.cancel(); if (window.HanoiLiveShare) window.HanoiLiveShare.onClose(); if (window.HanoiMapUi) window.HanoiMapUi.close(); window.HanoiFixedShare.onClose(); window.HanoiMyLocation.cancel(); visible = false; clearInterval(timer); timer = null; pending = null; api.show('home'); }
  function reset() { if (map && config) map.easeTo({ center: config.center, zoom: config.zoom, duration: 500 }); }
  function destroy() { if (window.HanoiQuickShare) window.HanoiQuickShare.destroy(); if (window.HanoiLiveShare) window.HanoiLiveShare.destroy(); if (window.HanoiMapUi) window.HanoiMapUi.destroy(); window.HanoiFixedShare.destroy(); window.HanoiMyLocation.destroy(); disposed = true; clearInterval(timer); pending = null; if (map) map.remove(); map = null; mapLoaded = false; seen.clear(); }
  return { bind: x => {
    api = x;
    if (window.HanoiMapUi) window.HanoiMapUi.bind({ focusSelf: () => window.HanoiMyLocation.focusSelf() });
    window.HanoiFixedShare.bind({ getMap: () => map, getConfig: () => config,
      isVisible: () => visible && !disposed && !document.getElementById('mapView').hidden, active: () => api.active(),
      openMap: () => open(false), send: x.send, toast: x.toast,
      onFixedPeer: () => { if (window.HanoiLiveShare) window.HanoiLiveShare.forgetPeer(); },
      quickAction: !!window.HanoiQuickShare,
      onDelivery: state => { if (window.HanoiQuickShare) window.HanoiQuickShare.onDelivery(state); } });
    window.HanoiMyLocation.bind({ getMap: () => map, getConfig: () => config,
      isVisible: () => visible && !disposed && !document.getElementById('mapView').hidden,
      onFix: fix => window.HanoiFixedShare.setLocalFix(fix) });
    if (window.HanoiLiveShare) window.HanoiLiveShare.bind({
      isVisible: () => visible && !disposed && !document.getElementById('mapView').hidden, active: () => api.active(), openMap: () => open(false),
      send: x.send, toast: x.toast,
      cancelOneShot: () => { window.HanoiFixedShare.onClose(); window.HanoiMyLocation.cancel(); },
      showLocalFix: fix => window.HanoiMyLocation.showLiveFix(fix),
      presentPeer: (value, focus) => window.HanoiFixedShare.presentLive(value, focus),
      canStart: () => !window.HanoiQuickShare || window.HanoiQuickShare.canStartLive()
    });
    if (window.HanoiQuickShare) window.HanoiQuickShare.bind({
      // Latched on the first 'load'. map.isStyleLoaded() turns false for a moment on every live position update
      // (the markers' GeoJSON is re-parsed), which flipped Gửi vị trí / Chia sẻ trực tiếp disabled and enabled
      // every second: the buttons blinked while both phones shared live.
      isReady: () => !!map && mapLoaded,
      isVisible: () => visible && !disposed && !document.getElementById('mapView').hidden,
      liveActive: () => window.HanoiLiveShare && window.HanoiLiveShare.isActive(),
      locate: callback => window.HanoiMyLocation.locate(callback),
      sendFixed: () => window.HanoiFixedShare.send()
    });
  }, open, close, receive, reset, destroy };
})();
