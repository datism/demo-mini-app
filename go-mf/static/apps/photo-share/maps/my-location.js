/* Local GPS acquisition. A validated snapshot becomes shareable only through the explicit send button. */
window.HanoiMyLocation = (() => {
  'use strict';
  const SOURCE = 'my-location';
  const FILL = 'my-location-accuracy';
  const DOT = 'my-location-dot';
  let api, pending = null, deadline = null, disposed = false, completion = null, displayedFix = null, renderedLive = false;
  const element = id => document.getElementById(id);
  const errors = {
    permission_denied: 'Chưa được cấp quyền vị trí. Bạn có thể cấp quyền trong Cài đặt ứng dụng rồi thử lại.',
    permission_pending: 'Hộp thoại quyền vị trí đang mở. Hãy xử lý hộp thoại rồi bấm lại.',
    permission_timeout: 'Đã hết thời gian chờ cấp quyền. Bấm Gửi vị trí để thử lại.',
    location_disabled: 'Chưa có nguồn vị trí khả dụng. Hãy bật Vị trí trên điện thoại; GPS cần quyền vị trí chính xác.',
    timeout: 'Chưa lấy được vị trí mới. Thử ở nơi thoáng, bật vị trí chính xác rồi bấm lại.',
    unavailable: 'Không truy cập được nguồn vị trí. Kiểm tra quyền và cài đặt Vị trí của điện thoại.',
    background: 'Đã dừng lấy vị trí khi ứng dụng ra nền. Bấm lại để lấy vị trí mới.'
  };
  function status(text, compact = text, level = 'normal') {
    const node = element('myLocationStatus');
    node.textContent = text; node.hidden = !text;
    if (window.HanoiMapUi) window.HanoiMapUi.set('local', compact, level);
  }
  const compactErrors = {
    permission_denied: 'Hãy cấp quyền Vị trí trong Cài đặt',
    permission_pending: 'Hãy xử lý hộp thoại quyền vị trí',
    permission_timeout: 'Hết thời gian cấp quyền · Thử lại',
    location_disabled: 'Hãy bật Vị trí trên điện thoại',
    timeout: 'Chưa lấy được vị trí · Thử lại',
    unavailable: 'Kiểm tra quyền và bật Vị trí rồi thử lại',
    background: 'Đã dừng lấy vị trí · Bấm để lấy lại'
  };
  function button(busy) {
    const node = element('btnMyLocation');
    if (!node) return;
    node.disabled = busy;
    node.textContent = busy ? 'Đang lấy vị trí…' : 'Vị trí của tôi';
    node.setAttribute('aria-busy', String(busy));
  }
  function stopPending() {
    const id = pending;
    pending = null; clearTimeout(deadline); deadline = null;
    button(false);
    if (id) {
      const bridge = window.AndroidBridge;
      try { if (bridge && typeof bridge.cancelMapLocation === 'function') bridge.cancelMapLocation(id); } catch (_) {}
    }
  }
  function clearPoint() {
    const map = api && api.getMap();
    if (!map) return;
    for (const id of [DOT, FILL]) if (map.getLayer(id)) map.removeLayer(id);
    if (map.getSource(SOURCE)) map.removeSource(SOURCE);
  }
  // Geodesic radius in metres, independent of zoom (no external GIS library).
  function circle(lng, lat, radius) {
    const rad = Math.PI / 180, phi = lat * rad, lambda = lng * rad, distance = Math.min(radius, 20000000) / 6371008.8;
    const coordinates = [];
    for (let i = 0; i < 64; i++) {
      const bearing = i * 2 * Math.PI / 64;
      const y = Math.asin(Math.sin(phi) * Math.cos(distance) + Math.cos(phi) * Math.sin(distance) * Math.cos(bearing));
      const x = lambda + Math.atan2(Math.sin(bearing) * Math.sin(distance) * Math.cos(phi), Math.cos(distance) - Math.sin(phi) * Math.sin(y));
      coordinates.push([((x / rad + 540) % 360) - 180, y / rad]);
    }
    coordinates.push(coordinates[0].slice());
    return coordinates;
  }
  function render(fix, live = false) {
    const map = api.getMap(), config = api.getConfig();
    if (!map || !map.isStyleLoaded() || !config) throw new Error('Bản đồ chưa sẵn sàng. Bấm lại sau khi bản đồ nạp xong.');
    const lng = fix.longitude, lat = fix.latitude, accuracy = fix.accuracy;
    const bounds = config.bounds;
    const focus = !live || !renderedLive;
    displayedFix = {...fix}; renderedLive = live;
    clearPoint();
    if (lng < bounds[0][0] || lng > bounds[1][0] || lat < bounds[0][1] || lat > bounds[1][1]) {
      if (window.HanoiMapUi) window.HanoiMapUi.self(fix, live ? 'live' : 'static', false);
      status('Đã lấy được vị trí, nhưng nằm ngoài vùng bản đồ Hà Nội đã tải. Chưa thể hiển thị tại đây.', 'Vị trí của tôi nằm ngoài vùng bản đồ', 'warning');
      return;
    }
    const data = { type: 'FeatureCollection', features: [
      { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [circle(lng, lat, accuracy)] } },
      { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [lng, lat] } }
    ] };
    map.addSource(SOURCE, { type: 'geojson', data });
    map.addLayer({ id: FILL, type: 'fill', source: SOURCE, filter: ['==', '$type', 'Polygon'],
      paint: { 'fill-color': '#1677ff', 'fill-opacity': 0.16 } });
    map.addLayer({ id: DOT, type: 'circle', source: SOURCE, filter: ['==', '$type', 'Point'],
      paint: { 'circle-radius': 7, 'circle-color': '#1677ff', 'circle-stroke-width': 3, 'circle-stroke-color': '#ffffff' } });
    if (window.HanoiMapUi) window.HanoiMapUi.self(fix, live ? 'live' : 'static', true);
    if (focus) focusSelf();
    const time = new Date(fix.timestamp).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    const detail = (fix.approximate ? 'Vị trí gần đúng' : 'Vị trí của tôi') + ' · Sai số khoảng ' + Math.ceil(accuracy) + ' m · ' + time;
    if (live) { element('myLocationStatus').textContent = detail + ' · Chia sẻ trực tiếp'; element('myLocationStatus').hidden = false; }
    else status(detail + ' · Không tự cập nhật', 'Đã lấy vị trí');
  }
  function complete(result) { const callback = completion; completion = null; if (callback) callback(result); }
  function locate(onComplete) {
    const callback = typeof onComplete === 'function' ? onComplete : null;
    if (disposed || pending || !api.isVisible() || window.HanoiLiveShare && window.HanoiLiveShare.isActive()) { if (callback) callback({ok:false,error:'busy'}); return; }
    const map = api.getMap();
    if (!map || !map.isStyleLoaded()) { status('Bản đồ đang nạp. Hãy bấm lại sau khi bản đồ hiển thị.', 'Bản đồ đang nạp…'); if (callback) callback({ok:false,error:'map_loading'}); return; }
    const bridge = window.AndroidBridge;
    if (!bridge || typeof bridge.requestMapLocation !== 'function') {
      status('Cần cài APK hỗ trợ vị trí mới để dùng tính năng này.', 'Cần cập nhật ứng dụng', 'error'); if (callback) callback({ok:false,error:'old_apk'}); return;
    }
    completion = callback;
    if (api.onFix) api.onFix(null);
    pending = 'loc_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2);
    const id = pending;
    button(true);
    status('Đang lấy vị trí trên máy này. Nếu được hỏi, hãy cấp quyền Vị trí; GPS có thể cần khoảng 45 giây ở nơi thoáng.', 'Đang lấy vị trí…');
    // Includes time spent on the Android permission dialog plus the GPS deadline.
    deadline = setTimeout(() => {
      if (pending !== id) return;
      stopPending(); status(errors.timeout, compactErrors.timeout, 'warning'); complete({ok:false,error:'timeout'});
    }, 140000);
    try { bridge.requestMapLocation(id); }
    catch (_) { stopPending(); status(errors.unavailable, compactErrors.unavailable, 'warning'); complete({ok:false,error:'unavailable'}); }
  }
  function onNativeResult(result) {
    if (disposed || !pending || !result || result.id !== pending) return;
    stopPending();
    if (!api.isVisible()) { complete({ok:false,error:'cancelled'}); return; }
    if (result.ok !== true) { status(errors[result.error] || errors.unavailable, compactErrors[result.error] || compactErrors.unavailable, 'warning'); complete({ok:false,error:result.error}); return; }
    if (![result.latitude, result.longitude, result.accuracy, result.timestamp, result.ageMs].every(Number.isFinite) ||
        result.latitude < -90 || result.latitude > 90 || result.longitude < -180 || result.longitude > 180 ||
        result.accuracy <= 0 || result.ageMs < 0 || result.ageMs > 30000 || result.timestamp <= 0) {
      status('Tọa độ nhận được không hợp lệ hoặc đã cũ. Hãy bấm lại.', 'Cần lấy lại vị trí', 'warning'); complete({ok:false,error:'invalid'}); return;
    }
    if (window.HanoiMapUi) window.HanoiMapUi.self(result);
    if (api.onFix) api.onFix({ latitude: result.latitude, longitude: result.longitude, accuracy: result.accuracy, timestamp: result.timestamp, ageMs: result.ageMs, approximate: result.approximate === true });
    try { render(result); } catch (error) { clearPoint(); status(error.message, 'Chưa hiện được vị trí trên bản đồ', 'warning'); }
    complete({ok:true,fix:result});
  }
  function cancel() { complete({ok:false,error:'cancelled'}); displayedFix = null; renderedLive = false; stopPending(); clearPoint(); status(''); if (window.HanoiMapUi) window.HanoiMapUi.self(null); if (api.onFix) api.onFix(null); }
  function onVisibility() {
    if (document.hidden) { cancel(); }
  }
  function bind(options) {
    api = options;
    if (element('btnMyLocation')) element('btnMyLocation').onclick = locate;
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('pagehide', cancel);
  }
  function destroy() {
    cancel(); disposed = true;
    document.removeEventListener('visibilitychange', onVisibility);
    window.removeEventListener('pagehide', cancel);
  }
  function showLiveFix(fix) {
    if (disposed) return;
    if (window.HanoiMapUi) window.HanoiMapUi.self(fix);
    try { render(fix, true); } catch (error) { status(error.message, 'Chưa hiện được điểm', 'warning'); }
  }
  function focusSelf() {
    if (!displayedFix || !api.isVisible()) return;
    const map = api.getMap(), config = api.getConfig(), fix = displayedFix;
    if (!map || !map.isStyleLoaded() || !config) return;
    const b = config.bounds;
    if (fix.longitude < b[0][0] || fix.longitude > b[1][0] || fix.latitude < b[0][1] || fix.latitude > b[1][1]) return;
    map.easeTo({center:[fix.longitude,fix.latitude],zoom:Math.min(fix.accuracy>1000?12:fix.accuracy>200?14:16,config.maxZoom),duration:450});
  }
  return { bind, locate, onNativeResult, cancel, destroy, circle, showLiveFix, focusSelf };
})();
