/* Offline map preview only: no device location APIs, no route calculation. */
window.HanoiViewer = (() => {
  let api, map, config, opening, timer, pending, visible = false, disposed = false, lastError = null;
  const seen = new Set();
  const status = text => { document.getElementById('mapStatus').textContent = text; };
  const message = (t, id) => ({ t, version: 1, id });
  async function ensureMap() {
    if (map) { map.resize(); return; }
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
      map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
      map.on('load', () => {
        if (disposed) return;
        if (!lastError) status('Đã nạp bản đồ chi tiết · ' + config.regionName);
        if (pending) transmit();
      });
      map.on('error', event => {
        lastError = String(event.error && event.error.message || 'Lỗi không xác định');
        console.error('[HANOI_MAP]', lastError);
        status('Lỗi bản đồ: ' + lastError.slice(0, 400));
      });
      map.on('sourcedata', event => {
        if (event.sourceId === 'hanoi' && event.sourceDataType === 'content' && event.tile && !lastError) {
          status('Bản đồ offline · Phóng to để xem tên đường · ' + config.regionName);
        }
      });
      map.on('webglcontextlost', () => status('Mất ngữ cảnh đồ họa. Đóng rồi mở lại bản đồ hoặc khởi động lại cuộc gọi.'));
    })().catch(e => { status(e.message); }).finally(() => { opening = null; });
    return opening;
  }
  function transmit() {
    if (!pending || disposed) return;
    api.send(message('map.open', pending.id));
    if (++pending.attempt >= 12) {
      clearInterval(timer); timer = null;
      status('Đã gửi yêu cầu mở bản đồ; chưa nhận xác nhận từ máy kia. Có thể bấm lại để thử.');
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
      transmit(); timer = setInterval(transmit, 1500);
    }
  }
  function receive(m) {
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
      if (m.t === 'map.busy') api.toast('Máy kia đang chia sẻ nội dung khác.');
      else api.toast('Máy kia đã nhận yêu cầu mở bản đồ.');
    }
    return true;
  }
  function close() { visible = false; clearInterval(timer); timer = null; pending = null; api.show('home'); }
  function reset() { if (map && config) map.easeTo({ center: config.center, zoom: config.zoom, duration: 500 }); }
  function destroy() { disposed = true; clearInterval(timer); pending = null; if (map) map.remove(); map = null; seen.clear(); }
  return { bind: x => { api = x; }, open, close, receive, reset, destroy };
})();
