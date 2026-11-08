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

// Natune contributors, GPL-3.0-or-later. Public /search/ and /track/ protocol from TidalInstanceResolver.
async function searchTracks(query,limit,context){
  const root=await Natune.json(Natune.query(Natune.base(context),'/search/',{s:String(query),q:String(query),limit:String(Natune.limit(limit))}));
  const items=Array.isArray(root)?root:root.data?.tracks?.items||root.tracks?.items||root.data?.items||root.items||root.data;
  if(!Array.isArray(items))throw Natune.error('INVALID_RESPONSE','TIDAL instance returned no search tracks');
  return items.map(v=>{const artists=(v.artists||[v.artist]).map(x=>x?.name||x).filter(Boolean);const title=v.version&&!String(v.title).toLowerCase().includes(String(v.version).toLowerCase())?v.title+' ('+v.version+')':v.title;
    return {id:String(v.id||v.trackId||''),title,artists,album:v.album?.title,duration:Number(v.duration)||undefined,explicit:typeof v.explicit==='boolean'?v.explicit:undefined,isrc:v.isrc};
  }).filter(t=>t.id&&t.title&&t.artists.length).slice(0,Natune.limit(limit));
}
async function getTrackStreamUrl(trackId,quality,context){
  const base=Natune.base(context),requested=['HI_RES_LOSSLESS','LOSSLESS','HIGH','LOW'].includes(quality)?quality:'LOSSLESS';
  const root=await Natune.json(Natune.query(base,'/track/',{id:String(trackId),quality:requested}));
  if(Natune.preview(root))throw Natune.error('UNSUPPORTED_AUDIO','TIDAL instance returned a preview');
  const info=root.data||root;
  if(info.url||info.stream_url)return Object.assign(Natune.audio(info.url||info.stream_url,info),{provider:'TIDAL instance',variant:requested,track:{sourceId:String(trackId)}});
  if(typeof info.manifest!=='string')throw Natune.error('UNSUPPORTED_AUDIO','TIDAL instance returned no manifest; queued download jobs are unsupported');
  const encoded=info.manifest.replace(/-/g,'+').replace(/_/g,'/');
  const manifest=new TextDecoder().decode(Uint8Array.from(atob(encoded+'='.repeat((4-encoded.length%4)%4)),c=>c.charCodeAt(0)));
  if(info.manifestMimeType==='application/dash+xml'){
    if(/<(?:[\w-]+:)?ContentProtection\b/i.test(manifest))throw Natune.error('UNSUPPORTED_AUDIO','TIDAL instance returned protected DASH');
    // The host fetches the inline root and handles its actual segment URLs with Media3.
    return {url:base+'/track/?id='+encodeURIComponent(trackId),inlineManifest:manifest,mimeType:'application/dash+xml',codecs:manifest.match(/codecs\s*=\s*"([^"]+)"/)?.[1]||'',provider:'TIDAL instance',variant:requested+'-dash',track:{sourceId:String(trackId)}};
  }
  const bts=JSON.parse(manifest);
  if(bts.encryptionType&&bts.encryptionType!=='NONE')throw Natune.error('UNSUPPORTED_AUDIO','TIDAL instance returned encrypted audio');
  const url=bts.urls?.find(v=>/^https?:\/\//.test(v));if(!url)throw Natune.error('UNSUPPORTED_AUDIO','TIDAL instance manifest has no stream URL');
  return Object.assign(Natune.audio(url,bts),{provider:'TIDAL instance',variant:requested,track:{sourceId:String(trackId)}});
}
module.exports={searchTracks,getTrackStreamUrl};
