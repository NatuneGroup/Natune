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
const API = 'https://www.qobuz.com/api.json/0.2';
const FORMATS = [27, 7, 6, 5];
const SHIFTS = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
const CONSTANTS = Array.from({length: 64}, (_, index) => Math.floor(Math.abs(Math.sin(index + 1)) * 4294967296) | 0);
let session = null;
let retryAt = 0;

function md5(text) {
  const data = new TextEncoder().encode(String(text));
  const total = Math.ceil((data.length + 9) / 64) * 64;
  const buffer = new Uint8Array(total);
  buffer.set(data);
  buffer[data.length] = 0x80;
  const bits = data.length * 8;
  for (let index = 0; index < 8; index++) buffer[total - 8 + index] = Math.floor(bits / Math.pow(2, 8 * index)) & 255;
  const state = [0x67452301, 0xefcdab89 | 0, 0x98badcfe | 0, 0x10325476];
  const words = new Array(16);
  for (let offset = 0; offset < total; offset += 64) {
    for (let index = 0; index < 16; index++) {
      const at = offset + index * 4;
      words[index] = buffer[at] | buffer[at + 1] << 8 | buffer[at + 2] << 16 | buffer[at + 3] << 24;
    }
    let [a, b, c, d] = state;
    for (let index = 0; index < 64; index++) {
      const round = index >> 4;
      const mixed = round === 0 ? (b & c) | (~b & d) : round === 1 ? (d & b) | (~d & c) : round === 2 ? b ^ c ^ d : c ^ (b | ~d);
      const word = round === 0 ? index : round === 1 ? (5 * index + 1) % 16 : round === 2 ? (3 * index + 5) % 16 : (7 * index) % 16;
      const shift = SHIFTS[round * 4 + (index % 4)];
      const sum = (mixed + a + CONSTANTS[index] + words[word]) | 0;
      a = d;
      d = c;
      c = b;
      b = (b + ((sum << shift) | (sum >>> (32 - shift)))) | 0;
    }
    state[0] = (state[0] + a) | 0;
    state[1] = (state[1] + b) | 0;
    state[2] = (state[2] + c) | 0;
    state[3] = (state[3] + d) | 0;
  }
  return state.map(word => [0, 8, 16, 24].map(shift => ((word >>> shift) & 255).toString(16).padStart(2, '0')).join('')).join('');
}

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
    appId: setting(context, 'appId'),
    appSecret: setting(context, 'appSecret'),
    userAuthToken: setting(context, 'userAuthToken'),
    email: setting(context, 'email'),
    password: typeof context?.settings?.password?.value === 'string' ? context.settings.password.value : '',
  };
  if (!/^\d+$/.test(config.appId)) throw configurationError('Qobuz app ID is not configured');
  if (!config.appSecret) throw configurationError('Qobuz app secret is not configured');
  if (!config.userAuthToken && !(config.email && config.password)) throw configurationError('Qobuz account is not configured');
  return config;
}

async function request(config, path, params, token) {
  const headers = {'X-App-Id': config.appId, Accept: 'application/json'};
  if (token) headers['X-User-Auth-Token'] = token;
  const query = new URLSearchParams(Object.assign({app_id: config.appId}, params));
  if (Date.now() < retryAt) { const error = new Error('Qobuz requests are cooling down'); error.code = 'RATE_LIMITED'; throw error; }
  const response = await fetch(API + path + '?' + query.toString(), {headers});
  if (response.status === 429 || response.status === 503) {
    const header = response.headers.get('Retry-After');
    const delay = /^\d+$/.test(header || '') ? Number(header) * 1000 : Date.parse(header) - Date.now();
    retryAt = Date.now() + Math.min(21600000, Math.max(1000, Number.isFinite(delay) && delay > 0 ? delay : 60000));
    const error = new Error('Qobuz rate limited or temporarily unavailable; wait for the server cooldown'); error.code = 'RATE_LIMITED'; throw error;
  }
  let value = null;
  try { value = JSON.parse(await response.text()); } catch (_) {}
  return {status: response.status, ok: response.ok, value};
}

async function login(config) {
  const key = config.appId + '\n' + config.email + '\n' + md5(config.password);
  if (session?.key === key) return session.token;
  const response = await request(config, '/user/login', {email: config.email, password: md5(config.password)});
  if (response.status === 400 || response.status === 401) throw configurationError('Qobuz rejected the account credentials');
  if (!response.ok) throw new Error('Qobuz login failed with HTTP ' + response.status);
  const token = response.value?.user_auth_token;
  if (typeof token !== 'string' || !token) throw new Error('Qobuz login returned no session');
  session = {key, token};
  return token;
}

async function userToken(config, rejected) {
  if (rejected) session = null;
  return (!rejected && config.userAuthToken) || login(config);
}

async function authenticated(config, path, params) {
  let token = await userToken(config, false);
  for (let attempt = 0; ; attempt++) {
    const response = await request(config, path, typeof params === 'function' ? params() : params, token);
    if (response.status === 401) {
      if (attempt > 0 || !(config.email && config.password)) throw configurationError('Qobuz rejected the session');
      token = await userToken(config, true);
      continue;
    }
    if (response.status === 400 && /signature/i.test(String(response.value?.message))) throw configurationError('Qobuz rejected the app secret');
    if (!response.ok) {
      const error = new Error('Qobuz request failed with HTTP ' + response.status);
      error.code = response.status === 400 && /(?:format|quality|not available)/i.test(String(response.value?.message || '')) ? 'QUALITY_UNAVAILABLE' : 'REQUEST_FAILED';
      throw error;
    }
    if (!response.value || typeof response.value !== 'object') throw new Error('Qobuz returned an unreadable response');
    return response.value;
  }
}

function toTrack(item) {
  if (!item || item.id == null || typeof item.title !== 'string' || item.streamable === false) return null;
  const version = typeof item.version === 'string' ? item.version.trim() : '';
  const base = item.title.trim();
  const title = version && !base.toLowerCase().includes(version.toLowerCase()) ? base + ' (' + version + ')' : base;
  const artists = [item.performer?.name, item.album?.artist?.name]
    .map(name => typeof name === 'string' ? name.trim() : '').filter(Boolean);
  if (!title || !artists.length) return null;
  const track = {id: String(item.id), title, artists: Array.from(new Set(artists))};
  if (typeof item.album?.title === 'string' && item.album.title.trim()) track.album = item.album.title.trim();
  if (Number(item.duration) > 0) track.duration = Number(item.duration);
  if (typeof item.parental_warning === 'boolean') track.explicit = item.parental_warning;
  if (typeof item.isrc === 'string' && item.isrc) track.isrc = item.isrc;
  return track;
}

async function searchTracks(query, limit, context) {
  const config = credentials(context);
  const size = Math.max(1, Math.min(Number(limit) || 10, 50));
  const value = await authenticated(config, '/track/search', {query: String(query ?? ''), limit: String(size), offset: '0'});
  const items = value.tracks?.items;
  if (!Array.isArray(items)) throw new Error('Qobuz returned an invalid search response');
  return items.map(toTrack).filter(Boolean).slice(0, size);
}

async function resolveQuality(trackId, quality, context) {
  const config = credentials(context);
  const id = String(trackId);
  if (!/^\d+$/.test(id)) throw new Error('Invalid Qobuz track ID');
  const format = FORMATS.includes(Number(quality)) ? Number(quality) : 6;
  const value = await authenticated(config, '/track/getFileUrl', () => {
    const timestamp = String(Math.floor(Date.now() / 1000));
    return {
      track_id: id,
      format_id: String(format),
      intent: 'stream',
      request_ts: timestamp,
      request_sig: md5('trackgetFileUrlformat_id' + format + 'intentstreamtrack_id' + id + timestamp + config.appSecret),
    };
  });
  if (value.sample === true) throw new Error('Qobuz returned a preview instead of the full track');
  const url = typeof value.url === 'string' ? value.url : '';
  if (!/^https?:\/\//.test(url)) throw new Error('Qobuz returned no stream URL');
  const actual = Number(value.format_id) || format;
  const mime = String(value.mime_type || '').toLowerCase() || (actual === 5 ? 'audio/mpeg' : 'audio/flac');
  const rate = Number(value.sampling_rate);
  const depth = Number(value.bit_depth);
  const stream = {
    url,
    mimeType: mime,
    codecs: mime === 'audio/mpeg' ? 'mp3' : mime === 'audio/flac' ? 'flac' : '',
    bitrate: actual === 5 ? 320000 : 0,
    variant: 'qobuz-' + actual + (depth > 0 ? '-' + depth + 'bit' : '') + (rate > 0 ? '-' + rate + 'khz' : ''),
    provider: 'Qobuz',
    track: {sourceId: String(value.track_id ?? id)},
  };
  if (rate > 0) stream.sampleRate = Math.round(rate < 1000 ? rate * 1000 : rate);
  const expires = Number(url.match(/[?&]etsp=(\d+)/)?.[1]) * 1000;
  if (expires > Date.now() + 60000 && expires <= Date.now() + 86400000) stream.expiresAtMs = expires;
  return stream;
}

async function getTrackStreamUrl(trackId, quality, context) {
  const requested = FORMATS.includes(Number(quality)) ? Number(quality) : 6;
  const tiers = FORMATS.slice(FORMATS.indexOf(requested));
  for (let index = 0; index < tiers.length; index++) {
    try { return await resolveQuality(trackId, tiers[index], context); }
    catch (error) { if (error.code !== 'QUALITY_UNAVAILABLE' || index === tiers.length - 1) throw error; }
  }
}

return {searchTracks, getTrackStreamUrl};

})();

const InstanceProvider = (() => {
// Natune contributors, GPL-3.0-or-later. Community protocol from QobuzInstanceResolver.
async function searchTracks(query,limit,context){
  const root=await Natune.json(Natune.query(Natune.base(context),'/api/get-music',{q:String(query),offset:'0'}));
  return Natune.tracks(root).map(item=>{const artist=item.performer?.name||item.artist?.name||item.artist||item.album?.artist?.name;
    return {id:String(item.id??item.track_id??''),title:item.title||item.name,artists:Array.isArray(item.artists)?item.artists.map(v=>v.name||v):[artist].filter(Boolean),album:item.album?.title||item.album,duration:Number(item.duration)||undefined,isrc:item.isrc};
  }).filter(t=>t.id&&t.title&&t.artists.length).slice(0,Natune.limit(limit));
}
async function getTrackStreamUrl(trackId,quality,context){
  const format=[27,7,6,5].includes(Number(quality))?Number(quality):6;
  const root=await Natune.json(Natune.query(Natune.base(context),'/api/download-music',{track_id:String(trackId),quality:String(format)}));
  if(Natune.preview(root))throw Natune.error('UNSUPPORTED_AUDIO','Instance returned a preview');
  const v=root.data||root.result||root;
  const url=v.url||v.downloadUrl||v.download_url||v.stream_url||v.streamUrl||v.link;
  if(!url)throw Natune.error('UNSUPPORTED_AUDIO','Instance returned no direct stream URL');
  return Object.assign(Natune.audio(url,v),{provider:'Qobuz instance',track:{sourceId:String(trackId)},variant:'format-'+format});
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
