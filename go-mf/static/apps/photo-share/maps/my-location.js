/* Local GPS acquisition. A validated snapshot becomes shareable only through the explicit send button. */
window.HanoiMyLocation = (() => {
  'use strict';
  const SOURCE = 'my-location';
  const FILL = 'my-location-accuracy';
  const DOT = 'my-location-dot';
  let api, pending = null, deadline = null, disposed = false;
  const element = id => document.getElementById(id);
  const errors = {
    permission_denied: 'Chưa được cấp quyền vị trí. Bạn có thể cấp quyền trong Cài đặt ứng dụng rồi thử lại.',
    permission_pending: 'Hộp thoại quyền vị trí đang mở. Hãy xử lý hộp thoại rồi bấm lại.',
    permission_timeout: 'Đã hết thời gian chờ cấp quyền. Bấm Vị trí của tôi để thử lại.',
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
    permission_denied: 'Chưa có quyền vị trí · Xem chi tiết',
    permission_pending: 'Hãy xử lý hộp thoại quyền vị trí',
    permission_timeout: 'Hết thời gian cấp quyền · Thử lại',
    location_disabled: 'Hãy bật Vị trí trên điện thoại',
    timeout: 'Chưa lấy được vị trí · Thử lại',
    unavailable: 'Chưa truy cập được vị trí · Xem chi tiết',
    background: 'Đã dừng lấy vị trí · Bấm để lấy lại'
  };
  function button(busy) {
    const node = element('btnMyLocation');
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
  function render(fix) {
    const map = api.getMap(), config = api.getConfig();
    if (!map || !map.isStyleLoaded() || !config) throw new Error('Bản đồ chưa sẵn sàng. Bấm lại sau khi bản đồ nạp xong.');
    const lng = fix.longitude, lat = fix.latitude, accuracy = fix.accuracy;
    const bounds = config.bounds;
    clearPoint();
    if (lng < bounds[0][0] || lng > bounds[1][0] || lat < bounds[0][1] || lat > bounds[1][1]) {
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
    map.easeTo({ center: [lng, lat], zoom: Math.min(accuracy > 1000 ? 12 : accuracy > 200 ? 14 : 16, config.maxZoom), duration: 600 });
    const time = new Date(fix.timestamp).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    status((fix.approximate ? 'Vị trí gần đúng' : 'Vị trí của tôi') + ' · Sai số khoảng ' + Math.ceil(accuracy) + ' m · ' + time + ' · Không tự cập nhật', 'Có thể gửi vị trí này');
  }
  function locate() {
    if (disposed || pending || !api.isVisible()) return;
    const map = api.getMap();
    if (!map || !map.isStyleLoaded()) { status('Bản đồ đang nạp. Hãy bấm lại sau khi bản đồ hiển thị.', 'Bản đồ đang nạp…'); return; }
    const bridge = window.AndroidBridge;
    if (!bridge || typeof bridge.requestMapLocation !== 'function') {
      status('Cần cài APK hỗ trợ vị trí mới để dùng tính năng này.', 'Cần cập nhật ứng dụng', 'error'); return;
    }
    clearPoint();
    if (api.onFix) api.onFix(null);
    if (window.HanoiMapUi) window.HanoiMapUi.self(null);
    pending = 'loc_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2);
    const id = pending;
    button(true);
    status('Đang lấy vị trí trên máy này. Nếu được hỏi, hãy cấp quyền Vị trí; GPS có thể cần khoảng 45 giây ở nơi thoáng.', 'Đang lấy vị trí…');
    // Includes time spent on the Android permission dialog plus the GPS deadline.
    deadline = setTimeout(() => {
      if (pending !== id) return;
      stopPending(); status(errors.timeout, compactErrors.timeout, 'warning');
    }, 140000);
    try { bridge.requestMapLocation(id); }
    catch (_) { stopPending(); status(errors.unavailable, compactErrors.unavailable, 'warning'); }
  }
  function onNativeResult(result) {
    if (disposed || !pending || !result || result.id !== pending) return;
    stopPending();
    if (!api.isVisible()) return;
    if (result.ok !== true) { status(errors[result.error] || errors.unavailable, compactErrors[result.error] || compactErrors.unavailable, 'warning'); return; }
    if (![result.latitude, result.longitude, result.accuracy, result.timestamp, result.ageMs].every(Number.isFinite) ||
        result.latitude < -90 || result.latitude > 90 || result.longitude < -180 || result.longitude > 180 ||
        result.accuracy <= 0 || result.ageMs < 0 || result.ageMs > 30000 || result.timestamp <= 0) {
      status('Tọa độ nhận được không hợp lệ hoặc đã cũ. Hãy bấm lại.', 'Cần lấy lại vị trí', 'warning'); return;
    }
    if (window.HanoiMapUi) window.HanoiMapUi.self(result);
    if (api.onFix) api.onFix({ latitude: result.latitude, longitude: result.longitude, accuracy: result.accuracy, timestamp: result.timestamp, ageMs: result.ageMs, approximate: result.approximate === true });
    try { render(result); } catch (error) { clearPoint(); status(error.message, 'Chưa hiện được vị trí · Xem chi tiết', 'warning'); }
  }
  function cancel() { stopPending(); clearPoint(); status(''); if (window.HanoiMapUi) window.HanoiMapUi.self(null); if (api.onFix) api.onFix(null); }
  function onVisibility() {
    if (document.hidden) { cancel(); }
  }
  function bind(options) {
    api = options;
    element('btnMyLocation').onclick = locate;
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('pagehide', cancel);
  }
  function destroy() {
    cancel(); disposed = true;
    document.removeEventListener('visibilitychange', onVisibility);
    window.removeEventListener('pagehide', cancel);
  }
  return { bind, locate, onNativeResult, cancel, destroy, circle };
})();
