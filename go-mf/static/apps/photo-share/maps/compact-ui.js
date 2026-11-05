/* Presentation only. Does not access GPS, send messages or alter map markers. */
window.HanoiMapUi = (() => {
  'use strict';
  let serial = 0;
  const entries = {}, node = id => document.getElementById(id);
  function refresh() {
    const chosen = entries.map && entries.map.level === 'error' ? entries.map :
      [entries.local, entries.share].filter(Boolean).sort((a,b) => b.order - a.order)[0] || entries.map;
    const target = node('mapCompactStatus');
    target.textContent = chosen ? chosen.text : 'Vị trí cố định · Chưa tự cập nhật';
    target.dataset.level = chosen ? chosen.level : 'normal';
  }
  function set(channel, text, level = 'normal') {
    entries[channel] = text ? { text, level, order: ++serial } : null;
    refresh();
  }
  function accuracy(value) { return value >= 1000 ? (value / 1000).toFixed(1) + ' km' : Math.ceil(value) + ' m'; }
  function time(value) { return new Date(value).toLocaleTimeString('vi-VN', {hour:'2-digit',minute:'2-digit'}); }
  function self(fix) {
    node('selfCompactRow').hidden = !fix;
    if (fix) node('selfCompactInfo').textContent = (fix.approximate ? 'Tôi (gần đúng)' : 'Tôi') + ' · ±' + accuracy(fix.accuracy) + ' · ' + time(fix.timestamp);
  }
  function peer(fix, covered) {
    node('peerCompactRow').hidden = !fix;
    if (fix) node('peerCompactInfo').textContent = 'Đối phương' + (fix.approximate ? ' (gần đúng)' : '') + ' · ±' + accuracy(fix.accuracy) + ' · ' + time(fix.timestamp) + (covered === false ? ' · Ngoài vùng' : '');
  }
  function collapse() {
    node('mapDetails').hidden = true;
    node('btnMapDetails').setAttribute('aria-expanded','false');
    node('btnMapDetails').textContent = 'Chi tiết';
  }
  function bind() {
    node('btnMapDetails').onclick = () => {
      const opening = node('mapDetails').hidden;
      node('mapDetails').hidden = !opening;
      node('btnMapDetails').setAttribute('aria-expanded',String(opening));
      node('btnMapDetails').textContent = opening ? 'Thu gọn' : 'Chi tiết';
    };
    collapse(); refresh();
  }
  function close() { collapse(); self(null); set('local',''); }
  function destroy() { collapse(); self(null); peer(null); for (const key of Object.keys(entries)) delete entries[key]; refresh(); }
  return { bind, set, self, peer, close, destroy };
})();
