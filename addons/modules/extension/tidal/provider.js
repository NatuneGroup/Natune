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

const AccountProvider = (() => {
const API = 'https://api.tidal.com/v1';
const QUALITIES = ['HI_RES_LOSSLESS', 'LOSSLESS', 'HIGH', 'LOW'];
const LOSSY_BITRATES = {HIGH: 320000, LOW: 96000};
let session = null;
let refreshed = null;
let retryAt = 0;

function configurationError(message) {
  const error = new Error(message);
  error.code = 'VERIFY_REQUIRED';
  return error;
}

function setting(context, key) {
  const value = context?.settings?.[key]?.value;
  return typeof value === 'string' ? value.trim() : '';
}

function credentials(context) {
  const config = {
    accessToken: setting(context, 'accessToken').replace(/^Bearer\s+/i, ''),
    countryCode: setting(context, 'countryCode').toUpperCase(),
    refreshToken: setting(context, 'refreshToken'),
    clientId: setting(context, 'clientId'),
    clientSecret: setting(context, 'clientSecret'),
  };
  if (config.countryCode && !/^[A-Z]{2}$/.test(config.countryCode)) throw configurationError('TIDAL country code must be two letters');
  if (!config.accessToken && !(config.refreshToken && config.clientId)) throw configurationError('Configure a TIDAL access token or refresh token and OAuth client ID');
  return config;
}

function decodeBase64(value) {
  const text = String(value).replace(/-/g, '+').replace(/_/g, '/');
  return new TextDecoder().decode(Uint8Array.from(atob(text + '='.repeat((4 - text.length % 4) % 4)), c => c.charCodeAt(0)));
}

function tokenExpiry(token) {
  try {
    const exp = Number(JSON.parse(decodeBase64(token.split('.')[1])).exp);
    return Number.isFinite(exp) && exp > 0 ? exp * 1000 : null;
  } catch (_) {
    return null;
  }
}

async function accountCountry(accessToken) {
  const response = await fetch(API + '/sessions', {headers: {Authorization: 'Bearer ' + accessToken, Accept: 'application/json'}});
  if (response.status === 429) rateLimit(response);
  if (response.status === 401) throw configurationError('TIDAL rejected the access token');
  if (!response.ok) throw new Error('TIDAL session lookup failed with HTTP ' + response.status);
  const country = (await response.json())?.countryCode;
  if (typeof country !== 'string' || !/^[A-Za-z]{2}$/.test(country)) throw new Error('TIDAL session has no country');
  return country.toUpperCase();
}

async function refreshAccess(config) {
  if (!(config.refreshToken && config.clientId)) throw configurationError('TIDAL session expired; configure your refresh token and OAuth client ID');
  const body = new URLSearchParams({grant_type: 'refresh_token', refresh_token: refreshed?.refreshToken || config.refreshToken, client_id: config.clientId});
  if (config.clientSecret) body.set('client_secret', config.clientSecret);
  const response = await fetch('https://auth.tidal.com/v1/oauth2/token', {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body.toString()});
  if (response.status === 429) rateLimit(response);
  if (!response.ok) throw configurationError('TIDAL refresh failed with HTTP ' + response.status);
  const data = await response.json();
  if (typeof data.access_token !== 'string' || !data.access_token) throw configurationError('TIDAL refresh returned no access token');
  refreshed = {key: JSON.stringify(config), accessToken: data.access_token,
    refreshToken: data.refresh_token || config.refreshToken, expiresAt: Date.now() + (Number(data.expires_in) || 300) * 1000};
  session = null;
  return refreshed.accessToken;
}

function rateLimit(response) {
  const header = response.headers.get('Retry-After');
  const seconds = Number(header);
  const delay = /^\d+$/.test(header || '') ? seconds * 1000 : Date.parse(header) - Date.now();
  retryAt = Date.now() + Math.min(21600000, Math.max(1000, Number.isFinite(delay) && delay > 0 ? delay : 60000));
  const error = new Error('TIDAL rate limited requests; retry after the server cooldown');
  error.code = 'RATE_LIMITED';
  throw error;
}

async function authorize(config) {
  const key = JSON.stringify(config);
  if (refreshed?.key !== key) refreshed = null;
  let accessToken = refreshed?.accessToken || config.accessToken;
  const expiry = refreshed?.expiresAt || tokenExpiry(accessToken);
  if (!accessToken || expiry !== null && expiry <= Date.now() + 60000) accessToken = await refreshAccess(config);
  if (session?.key === key && session.accessToken === accessToken) return session;
  let countryCode = config.countryCode;
  if (!countryCode) {
    try { countryCode = await accountCountry(accessToken); }
    catch (error) {
      if (error.code !== 'VERIFY_REQUIRED' || !(config.refreshToken && config.clientId)) throw error;
      accessToken = await refreshAccess(config);
      countryCode = await accountCountry(accessToken);
    }
  }
  session = {key, accessToken, countryCode};
  return session;
}

async function api(context, path, params) {
  if (Date.now() < retryAt) { const error = new Error('TIDAL requests are cooling down'); error.code = 'RATE_LIMITED'; throw error; }
  const config = credentials(context);
  let auth = await authorize(config);
  for (let attempt = 0; attempt < 2; attempt++) {
    const query = new URLSearchParams(Object.assign({}, params, {countryCode: auth.countryCode}));
    const url = API + path + '?' + query.toString();
    const response = await fetch(url, {headers: {Authorization: 'Bearer ' + auth.accessToken, Accept: 'application/json'}});
    if (response.status === 429) rateLimit(response);
    let data = null;
    try { data = await response.json(); } catch (_) {}
    if (response.status === 401 && Number(data?.subStatus ?? data?.sub_status) === 5003) {
      const error = new Error('TIDAL does not permit the requested quality'); error.code = 'QUALITY_RESTRICTED'; throw error;
    }
    if (response.status === 401) {
      session = null;
      if (attempt === 0 && config.refreshToken && config.clientId) { await refreshAccess(config); auth = await authorize(config); continue; }
      throw configurationError('TIDAL rejected the access token');
    }
    if (!response.ok) throw new Error('TIDAL request failed with HTTP ' + response.status);
    if (!data || typeof data !== 'object') throw new Error('TIDAL returned an unreadable response');
    return {url, data};
  }
}

function toTrack(item) {
  if (!item || item.id == null || typeof item.title !== 'string') return null;
  if (item.allowStreaming === false || item.streamReady === false) return null;
  const version = typeof item.version === 'string' ? item.version.trim() : '';
  const base = item.title.trim();
  const title = version && !base.toLowerCase().includes(version.toLowerCase()) ? base + ' (' + version + ')' : base;
  const artists = (Array.isArray(item.artists) && item.artists.length ? item.artists : [item.artist])
    .map(artist => typeof artist?.name === 'string' ? artist.name.trim() : '').filter(Boolean);
  if (!title || !artists.length) return null;
  const track = {id: String(item.id), title, artists: Array.from(new Set(artists))};
  if (typeof item.album?.title === 'string' && item.album.title.trim()) track.album = item.album.title.trim();
  if (Number(item.duration) > 0) track.duration = Number(item.duration);
  if (typeof item.explicit === 'boolean') track.explicit = item.explicit;
  if (typeof item.isrc === 'string' && item.isrc) track.isrc = item.isrc;
  return track;
}

async function searchTracks(query, limit, context) {
  const size = Math.max(1, Math.min(Number(limit) || 10, 50));
  const {data} = await api(context, '/search/tracks', {query: String(query ?? ''), limit: String(size), offset: '0'});
  if (!data || !Array.isArray(data.items)) throw new Error('TIDAL returned an invalid search response');
  return data.items.map(toTrack).filter(Boolean).slice(0, size);
}

function urlExpiry(url) {
  const match = url.match(/[?&](?:Expires|expires|exp)=(\d{9,13})(?:&|$)/) || url.match(/[?&]token=(\d{9,13})~/);
  if (!match) return null;
  const value = Number(match[1]) < 10000000000 ? Number(match[1]) * 1000 : Number(match[1]);
  return value > Date.now() + 60000 && value <= Date.now() + 86400000 ? value : null;
}

function mimeType(value) {
  const type = String(value || '').toLowerCase();
  if (type === 'audio/flac' || type === 'audio/x-flac') return 'audio/flac';
  if (type === 'audio/mp4' || type === 'audio/m4a' || type === 'audio/aac') return 'audio/mp4';
  return type;
}

async function resolveQuality(trackId, quality, context) {
  const requested = QUALITIES.includes(quality) ? quality : 'LOSSLESS';
  const id = encodeURIComponent(String(trackId));
  const {url: source, data: info} = await api(context, '/tracks/' + id + '/playbackinfopostpaywall',
    {audioquality: requested, playbackmode: 'STREAM', assetpresentation: 'FULL'});
  if (!info || typeof info.manifest !== 'string') throw new Error('TIDAL returned no playback manifest');
  if (info.assetPresentation && info.assetPresentation !== 'FULL') throw new Error('TIDAL returned a preview instead of the full track');
  const actual = typeof info.audioQuality === 'string' && info.audioQuality ? info.audioQuality : requested;
  const manifest = decodeBase64(info.manifest);
  const sampleRate = Number(info.sampleRate) > 0 ? Number(info.sampleRate) : undefined;
  const upstream = {sourceId: String(info.trackId ?? trackId)};
  if (info.manifestMimeType === 'application/vnd.tidal.bts') {
    const bts = JSON.parse(manifest);
    if (bts.encryptionType && bts.encryptionType !== 'NONE') throw new Error('TIDAL returned an encrypted stream');
    const url = Array.isArray(bts.urls) ? bts.urls.find(value => typeof value === 'string' && /^https?:\/\//.test(value)) : null;
    if (!url) throw new Error('TIDAL manifest has no stream URL');
    const codecs = typeof bts.codecs === 'string' ? bts.codecs : '';
    const stream = {
      url,
      mimeType: mimeType(bts.mimeType) || (codecs === 'flac' ? 'audio/flac' : 'audio/mp4'),
      codecs,
      bitrate: LOSSY_BITRATES[actual] || 0,
      variant: 'tidal-' + actual.toLowerCase() + (codecs ? '-' + codecs : ''),
      provider: 'TIDAL',
      track: upstream,
    };
    if (sampleRate) stream.sampleRate = sampleRate;
    const expiry = urlExpiry(url);
    if (expiry) stream.expiresAtMs = expiry;
    return stream;
  }
  if (info.manifestMimeType === 'application/dash+xml') {
    if (/<(?:[A-Za-z0-9_-]+:)?ContentProtection\b/i.test(manifest)) throw new Error('TIDAL returned a protected stream');
    const codecs = manifest.match(/codecs\s*=\s*"([^"]+)"/i)?.[1] || '';
    const rate = sampleRate || Number(manifest.match(/audioSamplingRate\s*=\s*"(\d+)"/i)?.[1]) || undefined;
    const stream = {
      url: source,
      inlineManifest: manifest,
      mimeType: 'application/dash+xml',
      codecs,
      bitrate: LOSSY_BITRATES[actual] || 0,
      variant: 'tidal-' + actual.toLowerCase() + (codecs ? '-' + codecs : '') + '-dash',
      provider: 'TIDAL',
      track: upstream,
    };
    if (rate) stream.sampleRate = rate;
    return stream;
  }
  throw new Error('TIDAL returned an unsupported manifest type');
}

async function getTrackStreamUrl(trackId, quality, context) {
  const requested = QUALITIES.includes(quality) ? quality : 'LOSSLESS';
  const tiers = requested === 'HI_RES_LOSSLESS' ? ['HI_RES_LOSSLESS', 'LOSSLESS', 'HIGH'] : requested === 'LOSSLESS' ? ['LOSSLESS', 'HIGH'] : [requested];
  for (let index = 0; index < tiers.length; index++) {
    try { return await resolveQuality(trackId, tiers[index], context); }
    catch (error) { if (error.code !== 'QUALITY_RESTRICTED' || index === tiers.length - 1) throw error; }
  }
}

return {searchTracks, getTrackStreamUrl};

})();

const InstanceProvider = (() => {
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
return {searchTracks, getTrackStreamUrl};

})();

// Natune contributors, GPL-3.0-or-later. One provider, explicit connection mode.
function connection(context) {
  const mode = Natune.text(context, 'mode') || 'account';
  if (mode === 'instance') return InstanceProvider;
  if (mode === 'account') return AccountProvider;
  throw Natune.error('VERIFY_REQUIRED', 'Choose an account or public instance connection');
}
async function searchTracks(query, limit, context) { return connection(context).searchTracks(query, limit, context); }
async function getTrackStreamUrl(trackId, quality, context) { return connection(context).getTrackStreamUrl(trackId, quality, context); }
module.exports = {searchTracks, getTrackStreamUrl};
