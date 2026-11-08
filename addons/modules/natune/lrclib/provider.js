// Natune contributors. GPL-3.0-or-later.
// A Natune lyrics extension; upstream ArchiveTune API v1 cannot load this kind.
async function getLyrics(track, context) {
  if (!track || !track.title || !track.artist) return null;
  const params = [
    ['track_name', track.title], ['artist_name', track.artist],
    ['album_name', track.album || ''], ['duration', track.duration > 0 ? track.duration : '']
  ].filter(([, value]) => value !== '').map(([key, value]) => key + '=' + encodeURIComponent(String(value))).join('&');
  const response = await fetch('https://lrclib.net/api/get?' + params, {
    headers: {'User-Agent': 'Natune LRCLIB extension/1.0.0 (https://github.com/NatuneGroup/Natune)'}
  });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error('LRCLIB request failed');
  const result = await response.json();
  if (result.instrumental) return null;
  if (typeof result.syncedLyrics === 'string' && result.syncedLyrics.trim()) {
    return {format: 'lrc', text: result.syncedLyrics};
  }
  if (typeof result.plainLyrics === 'string' && result.plainLyrics.trim()) {
    return {format: 'plain', text: result.plainLyrics};
  }
  return null;
}

module.exports = {getLyrics};
