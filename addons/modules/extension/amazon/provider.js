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

// Natune contributors, GPL-3.0-or-later. Amazon instance v1/v2 protocols from KtorAmazonRepository.
// Catalog candidates are real Amazon tracks. An instance must return its actual matched recording.
async function searchTracks(query,limit,context){
  // Resolve catalog recording metadata before requesting the same recording from the Amazon instance.
  const root=await Natune.json(Natune.query('https://api.deezer.com','/search/track',{q:String(query),limit:String(Natune.limit(limit))}));
  return Natune.tracks(root).map(v=>{const artists=Array.isArray(v.artists)?v.artists.map(x=>x.name||x):[v.artist?.name||v.artist||v.artist_name].filter(Boolean);
    const t={id:'',title:v.title||v.track_name||v.name,artists,album:v.album?.title||v.album||v.album_name,duration:Number(v.duration)||undefined};
    t.id=Natune.opaque({catalogId:v.id, id:v.asin,title:t.title,artist:artists.join(', '),album:t.album,duration:t.duration});return t;
  }).filter(v=>v.title&&v.artists.length).slice(0,Natune.limit(limit));
}
async function getTrackStreamUrl(trackId,quality,context){
  const selected=Natune.unpack(trackId),base=Natune.base(context),token=Natune.text(context,'operatorToken');
  const params={track:selected.title,artist:selected.artist,album:selected.album||'',duration:String(selected.duration||''),quality:String(quality||'LOSSLESS'),intent:'stream'};
  const headers={Accept:'application/json'};const jwt=Natune.text(context,'turnstileJwt');if(jwt)headers['X-Turnstile-JWT']=jwt;
  let root,modern=false;
  if(token){const r=await fetch(Natune.query(base,'/api/v2/track/',params),{headers:Object.assign({},headers,{Authorization:'Bearer '+token})});
    if(r.status===429||r.status===503)throw Natune.error('RATE_LIMITED','Amazon instance refused the request; honor Retry-After');
    if(r.ok){root=await r.json();modern=true;}else if(![401,403,428,404,502].includes(r.status))throw new Error('Amazon instance failed with HTTP '+r.status);}
  if(!root){const old=Object.assign({},params,{quality:quality==='HI_RES'?'hires':quality==='HIGH'?'high':'lossless'});if(token)old.bypass_token=token;root=await Natune.json(Natune.query(base,'/api/track/',old),{headers});}
  if(Natune.preview(root))throw Natune.error('UNSUPPORTED_AUDIO','Amazon instance returned a preview');
  const payload=modern?(Array.isArray(root.playback)?root.playback.find(v=>v.kind==='audio'&&v.delivery==='direct'&&(!v.source||v.source.toLowerCase()==='amazon')):null):[root,root.data,root.track,root.result].find(v=>v?.stream_url);
  const actual=modern?root.track:payload;
  if(!payload||!actual)throw Natune.error('UNSUPPORTED_AUDIO','Amazon instance returned no direct audio or recording metadata');
  // Never force a returned different recording to match the search result.
  const title=actual.title||actual.track_name||actual.name;
  const artist=actual.artist?.name||actual.artist||actual.artist_name;
  if(!title||!artist||String(title).toLowerCase()!==String(selected.title).toLowerCase()||!String(artist).toLowerCase().includes(String(selected.artist).toLowerCase()))
    throw Natune.error('RECORDING_MISMATCH','Amazon instance returned a different or unidentified recording');
  if(payload.decryption_key||payload.decryptionKey||payload.encryption||payload.drm||payload.encrypted)
    throw Natune.error('UNSUPPORTED_AUDIO','Amazon instance must return plaintext audio');
  return Object.assign(Natune.audio(payload.url||payload.stream_url,payload),{provider:'Amazon instance',variant:'amazon-'+String(quality||'LOSSLESS'),track:{sourceId:String(selected.id||trackId)}});
}
module.exports={searchTracks,getTrackStreamUrl};
