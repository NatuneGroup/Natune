// Natune contributors, GPL-3.0-or-later. Shared source is bundled into provider.js; no runtime imports.
const Natune = (() => {
  const cooling = new Map();
  function setting(context, key) { return context?.settings?.[key]?.value; }
  function text(context, key) { const value = setting(context, key); return typeof value === 'string' ? value.trim() : ''; }
  function error(code, message) { const failure = new Error(message); failure.code = code; return failure; }
  function webUrl(raw, httpsOnly = false) {
    const url = new URL(String(raw));
    if (!(httpsOnly ? url.protocol === 'https:' : ['http:', 'https:'].includes(url.protocol)) || !url.hostname || url.username || url.password)
      throw error('VERIFY_REQUIRED', 'Configure an absolute URL without embedded credentials');
    return url;
  }
  function base(context, key = 'instanceUrl') { const url = webUrl(text(context, key), true); if (url.search || url.hash) throw error('VERIFY_REQUIRED', 'Instance URL must have no query or fragment'); return url.toString().replace(/\/$/, ''); }
  function limit(value) { return Math.max(1, Math.min(Number(value) || 10, 50)); }
  async function request(url, options) {
    const origin = webUrl(url).origin;
    if (Date.now() < (cooling.get(origin) || 0)) throw error('RATE_LIMITED', 'This service is cooling down after a refusal');
    const response = await fetch(url, options);
    if ([429, 503].includes(response.status)) {
      const h = response.headers.get('Retry-After');
      const ms = /^\d+$/.test(h || '') ? Number(h) * 1000 : Date.parse(h) - Date.now();
      cooling.set(origin, Date.now() + Math.min(21600000, Math.max(1000, Number.isFinite(ms) && ms > 0 ? ms : 60000)));
      throw error('RATE_LIMITED', 'Service temporarily unavailable; honor the server cooldown');
    }
    if (!response.ok) throw error([401, 403].includes(response.status) ? 'VERIFY_REQUIRED' : 'REQUEST_FAILED', 'Service request failed with HTTP ' + response.status);
    return response;
  }
  async function json(url, options) { const r = await request(url, options); try { return await r.json(); } catch (_) { throw error('INVALID_RESPONSE', 'Service returned unreadable JSON'); } }
  function query(base, path, params) { return base + path + '?' + new URLSearchParams(params).toString(); }
  function preview(value) { return value?.sample === true || value?.is_preview === true || value?.preview === true || value?.assetPresentation === 'PREVIEW' || String(value?.type || '').toUpperCase() === 'PREVIEW'; }
  function tracks(value) { if (Array.isArray(value)) return value; for (const k of ['tracks', 'items', 'results', 'data']) { const v = value?.[k]; if (Array.isArray(v)) return v; if (v?.items && Array.isArray(v.items)) return v.items; } throw error('INVALID_RESPONSE', 'Service returned no track collection'); }
  function audio(url, value = {}) {
    const normalized = webUrl(url).toString();
    if (preview(value) || value.encryptionType && value.encryptionType !== 'NONE' || value.drm === true) throw error('UNSUPPORTED_AUDIO', 'The service returned preview or protected audio');
    const mime = value.mimeType || value.mime_type || (/\.flac(?:[?#]|$)/i.test(normalized) ? 'audio/flac' : /\.m3u8(?:[?#]|$)/i.test(normalized) ? 'application/x-mpegURL' : /\.mp3(?:[?#]|$)/i.test(normalized) ? 'audio/mpeg' : 'audio/mp4');
    const result = {url: normalized, mimeType: mime, codecs: value.codecs || value.codec || (mime === 'audio/flac' ? 'flac' : mime === 'audio/mpeg' ? 'mp3' : ''), bitrate: Number(value.bitrate) || 0};
    const rate = Number(value.sampleRate || value.sampling_rate || value.sample_rate);
    if (rate > 0) result.sampleRate = Math.round(rate < 1000 ? rate * 1000 : rate);
    if (value.headers && typeof value.headers === 'object') result.headers = value.headers;
    if (Number(value.expiresAtMs) > Date.now()) result.expiresAtMs = Number(value.expiresAtMs);
    if (Number(value.contentLength) > 0) result.contentLength = Number(value.contentLength);
    return result;
  }
  function opaque(value) { return encodeURIComponent(JSON.stringify(value)); }
  function unpack(id) { try { return JSON.parse(decodeURIComponent(id)); } catch (_) { throw error('INVALID_TRACK', 'Invalid provider track identifier'); } }
  return {setting, text, error, webUrl, base, limit, request, json, query, preview, tracks, audio, opaque, unpack};
})();

// Natune contributors, GPL-3.0-or-later. Plaintext instance protocol from DeezerInstanceResolver.
function toTrack(item) {
  const artists = (item.contributors || [item.artist]).map(v=>v?.name).filter(Boolean);
  if (item.id == null || !item.title || !artists.length) return null;
  return {id: String(item.id), title: item.title, artists, album: item.album?.title,
    duration: Number(item.duration) || undefined, explicit: !!item.explicit_lyrics, isrc: item.isrc};
}
async function searchTracks(query, limit) {
  const root = await Natune.json(Natune.query('https://api.deezer.com', '/search/track', {q:String(query),limit:String(Natune.limit(limit))}));
  if(root.error) throw Natune.error('REQUEST_FAILED','Deezer catalog rejected the search');
  return Natune.tracks(root).map(toTrack).filter(Boolean).slice(0,Natune.limit(limit));
}
async function getTrackStreamUrl(trackId, quality, context) {
  const base=Natune.base(context),headers={Range:'bytes=0-15'};
  const token=Natune.text(context,'instanceToken');if(token)headers.Authorization='Bearer '+token;
  const track=await Natune.json('https://api.deezer.com/track/'+encodeURIComponent(trackId));
  if(track.error)throw Natune.error('INVALID_TRACK','Deezer track is unavailable');
  const paths=[];if(track.isrc)paths.push('/stream/?isrc='+encodeURIComponent(track.isrc));paths.push('/stream/'+encodeURIComponent(trackId));
  let failure;
  for(const path of paths){try {
    const url=base+path,r=await Natune.request(url,{headers}),bytes=new Uint8Array(await r.arrayBuffer());
    // A server ignoring Range could exceed host memory bounds; require partial delivery.
    if(r.status!==206)throw Natune.error('UNSUPPORTED_AUDIO','Instance must honor bounded byte-range requests');
    const flac=bytes[0]===102&&bytes[1]===76&&bytes[2]===97&&bytes[3]===67;
    const mp3=bytes[0]===73&&bytes[1]===68&&bytes[2]===51||bytes[0]===255&&(bytes[1]&224)===224;
    if(!flac&&!mp3)throw Natune.error('UNSUPPORTED_AUDIO','Instance did not return plaintext FLAC or MP3');
    if(String(quality)==='FLAC'&&!flac)throw Natune.error('QUALITY_UNAVAILABLE','Instance returned MP3 for a FLAC request');
    const stream=Natune.audio(url,{mimeType:flac?'audio/flac':'audio/mpeg',codecs:flac?'flac':'mp3'});
    if(token)stream.headers={Authorization:'Bearer '+token};
    const total=Number(r.headers.get('Content-Range')?.split('/')[1]);if(total>0)stream.contentLength=total;
    return Object.assign(stream,{provider:'Deezer',track:{sourceId:String(trackId)},variant:flac?'flac':'mp3'});
  }catch(e){failure=e;if(['RATE_LIMITED','VERIFY_REQUIRED'].includes(e.code))throw e;}}
  throw failure||new Error('No Deezer instance stream');
}
module.exports={searchTracks,getTrackStreamUrl};
