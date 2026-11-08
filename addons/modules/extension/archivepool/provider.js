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

// Natune contributors, GPL-3.0-or-later. AES-256-GCM (96-bit IV, 128-bit tag), SHA-256.
// Implements the ArchivePool wire protocol using only standard JS, including BigInt (QuickJS API v1).
// No key is embedded. Authentication is checked before any plaintext is returned.
const PoolCrypto = (() => {
  const K = [0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
  const rotr = (x,n) => x >>> n | x << (32-n);
  function sha256(text) {
    const input = new TextEncoder().encode(text), bytes = new Uint8Array(Math.ceil((input.length+9)/64)*64);
    bytes.set(input); bytes[input.length] = 128;
    let length = BigInt(input.length)*8n;
    for (let i=0;i<8;i++) { bytes[bytes.length-1-i]=Number(length&255n); length>>=8n; }
    const h = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19], w=new Uint32Array(64);
    for (let at=0;at<bytes.length;at+=64) {
      for (let i=0;i<16;i++) w[i]=(bytes[at+i*4]<<24)|(bytes[at+i*4+1]<<16)|(bytes[at+i*4+2]<<8)|bytes[at+i*4+3];
      for (let i=16;i<64;i++) { const a=w[i-15],b=w[i-2]; w[i]=(w[i-16]+(rotr(a,7)^rotr(a,18)^a>>>3)+w[i-7]+(rotr(b,17)^rotr(b,19)^b>>>10))>>>0; }
      let [a,b,c,d,e,f,g,j]=h;
      for (let i=0;i<64;i++) { const t1=(j+(rotr(e,6)^rotr(e,11)^rotr(e,25))+((e&f)^(~e&g))+K[i]+w[i])>>>0,t2=((rotr(a,2)^rotr(a,13)^rotr(a,22))+((a&b)^(a&c)^(b&c)))>>>0; j=g;g=f;f=e;e=(d+t1)>>>0;d=c;c=b;b=a;a=(t1+t2)>>>0; }
      [a,b,c,d,e,f,g,j].forEach((v,i)=>h[i]=(h[i]+v)>>>0);
    }
    return Uint8Array.from(h.flatMap(v=>[v>>>24,v>>>16&255,v>>>8&255,v&255]));
  }
  const xtime = x => ((x<<1)^((x&128)?0x11b:0))&255;
  function multiply(a,b) { let r=0;for(let i=0;i<8;i++){if(b&1)r^=a;a=xtime(a);b>>=1;}return r; }
  const S=Uint8Array.from({length:256},(_,x)=>{let y=x?1:0;if(x)for(let i=0;i<254;i++)y=multiply(y,x);const rot=n=>((y<<n)|(y>>(8-n)))&255;return y^rot(1)^rot(2)^rot(3)^rot(4)^0x63;});
  function schedule(key) {
    if(key.length!==32)throw new Error('AES-256 requires a 32-byte key');
    const k=new Uint8Array(240);k.set(key);let rcon=1;
    for(let at=32;at<240;at+=4){let t=Array.from(k.slice(at-4,at));if(at%32===0){t=[S[t[1]]^rcon,S[t[2]],S[t[3]],S[t[0]]];rcon=xtime(rcon);}else if(at%32===16)t=t.map(v=>S[v]);for(let j=0;j<4;j++)k[at+j]=k[at-32+j]^t[j];}return k;
  }
  function aes(block,k) {
    let s=Uint8Array.from(block);for(let i=0;i<16;i++)s[i]^=k[i];
    for(let r=1;r<=14;r++){
      s=s.map(v=>S[v]);const t=s.slice();for(let row=0;row<4;row++)for(let col=0;col<4;col++)s[row+4*col]=t[row+4*((col+row)%4)];
      if(r!==14)for(let c=0;c<16;c+=4){const a=s[c],b=s[c+1],d=s[c+2],e=s[c+3],v=a^b^d^e;s[c]=a^v^xtime(a^b);s[c+1]=b^v^xtime(b^d);s[c+2]=d^v^xtime(d^e);s[c+3]=e^v^xtime(e^a);}
      for(let i=0;i<16;i++)s[i]^=k[r*16+i];
    }return s;
  }
  const integer=bytes=>bytes.reduce((a,b)=>(a<<8n)|BigInt(b),0n);
  function gmul(x,y){let z=0n,v=y;for(let i=127n;i>=0n;i--){if(x&(1n<<i))z^=v;v=(v>>1n)^((v&1n)?0xe1000000000000000000000000000000n:0n);}return z;}
  function decrypt(key,iv,sealed) {
    if(iv.length!==12||sealed.length<16||sealed.length>1048576)throw new Error('Invalid ArchivePool AES-GCM envelope');
    const k=schedule(key),ct=sealed.slice(0,-16),tag=sealed.slice(-16),h=integer(aes(new Uint8Array(16),k));let auth=0n;
    for(let at=0;at<ct.length;at+=16){const b=new Uint8Array(16);b.set(ct.slice(at,at+16));auth=gmul(auth^integer(b),h);}
    auth=gmul(auth^(BigInt(ct.length)*8n),h);const counter=new Uint8Array(16);counter.set(iv);counter[15]=1;
    const expected=auth^integer(aes(counter,k));let diff=expected^integer(tag);if(diff!==0n)throw new Error('ArchivePool authentication failed; verify your read key');
    const plain=new Uint8Array(ct.length);
    for(let at=0;at<ct.length;at+=16){for(let j=15;j>=12;j--){counter[j]=(counter[j]+1)&255;if(counter[j])break;}const block=aes(counter,k);for(let j=0;j<16&&at+j<ct.length;j++)plain[at+j]=ct[at+j]^block[j];}
    return plain;
  }
  function base64(value){if(!/^[A-Za-z0-9+/_=-]*$/.test(value))throw new Error('Invalid ArchivePool base64');const v=value.replace(/-/g,'+').replace(/_/g,'/');return Uint8Array.from(atob(v+'='.repeat((4-v.length%4)%4)),c=>c.charCodeAt(0));}
  function field(value,key){if(typeof value!=='string')return null;if(!value.startsWith('enc:1:'))return value;const parts=value.split(':');if(parts.length!==4)throw new Error('Invalid ArchivePool encrypted field');return new TextDecoder().decode(decrypt(key,base64(parts[2]),base64(parts[3])));}
  return {sha256,decrypt,field};
})();

const PoolTidal = (() => {
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
  const countryCode = config.countryCode || await accountCountry(accessToken);
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

const PoolQobuz = (() => {
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

// Natune contributors, GPL-3.0-or-later. ArchivePool GET /api/accounts v2 only; never reports or contributes accounts.
let poolCache=null;
let poolRetryAt=0;
function poolConfig(context){const key=Natune.text(context,'readKey');if(!key)throw Natune.error('VERIFY_REQUIRED','Configure your ArchivePool read key');return {base:Natune.base(context,'poolUrl'),key};}
async function accounts(context){
  const config=poolConfig(context),identity=config.base+'\n'+config.key;
  if(poolCache?.identity!==identity){poolCache=null;poolRetryAt=0;}
  if(Date.now()<poolRetryAt)throw Natune.error('RATE_LIMITED','ArchivePool requests are cooling down');
  if(poolCache&&Date.now()<poolCache.expires)return poolCache;
  const headers={Authorization:'Bearer '+config.key,'X-Pool-Client':'v2',Accept:'application/json'};
  if(poolCache?.etag)headers['If-None-Match']=poolCache.etag;
  const response=await fetch(config.base+'/api/accounts',{headers});
  if([401,403,429,503].includes(response.status)){
    const raw=response.headers.get('Retry-After'),seconds=Number(raw);
    poolRetryAt=Date.now()+Math.min(21600000,Math.max(1000,Number.isFinite(seconds)&&seconds>0?seconds*1000:60000));
    throw Natune.error([401,403].includes(response.status)?'VERIFY_REQUIRED':'RATE_LIMITED','ArchivePool refused this lease; verify the read key or wait for Retry-After');
  }
  if(response.status===304&&poolCache){poolCache.expires=Date.now()+1800000;return poolCache;}
  if(!response.ok)throw Natune.error('REQUEST_FAILED','ArchivePool failed with HTTP '+response.status);
  const root=await response.json(),key=PoolCrypto.sha256('archivepool-client:'+config.key);
  const byService={};
  for(const service of ['tidal','qobuz']){
    const rows=Array.isArray(root[service])?root[service]:root[service]?.accounts;
    byService[service]=(Array.isArray(rows)?rows:[]).slice(0,64).map((row,index)=>{
      const decoded={};for(const field of service==='tidal'?['token','refreshToken','countryCode']:['token','appId','appSecret'])decoded[field]=PoolCrypto.field(row[field],key);
      return {id:String(row.id??index),credentials:decoded};
    }).filter(row=>service==='tidal'?row.credentials.token:row.credentials.token&&row.credentials.appId&&row.credentials.appSecret);
  }
  if(!byService.tidal.length&&!byService.qobuz.length)throw Natune.error('VERIFY_REQUIRED','ArchivePool returned no usable TIDAL/Qobuz accounts');
  poolCache={identity,byService,expires:Date.now()+1800000,etag:response.headers.get('ETag')};return poolCache;
}
function providerContext(service,account,context){
  const c=account.credentials,values=service==='tidal'?{accessToken:c.token,countryCode:c.countryCode,refreshToken:c.refreshToken,clientId:Natune.text(context,'tidalClientId'),clientSecret:Natune.text(context,'tidalClientSecret')}:{userAuthToken:c.token,appId:c.appId,appSecret:c.appSecret};
  const settings={};for(const [key,value] of Object.entries(values))settings[key]={value:value||null};return {settings};
}
function services(context){return Natune.setting(context,'source')==='qobuz'?['qobuz','tidal']:['tidal','qobuz'];}
async function searchTracks(query,limit,context){
  const lease=await accounts(context),size=Natune.limit(limit);let failure;
  for(const service of services(context))for(const account of lease.byService[service].slice(0,4)){
    try{const tracks=await (service==='tidal'?PoolTidal:PoolQobuz).searchTracks(query,size,providerContext(service,account,context));
      if(tracks.length)return tracks.map(track=>Object.assign({},track,{id:Natune.opaque({service,account:account.id,track:track.id})}));
    }catch(e){failure=e;if(e.code==='RATE_LIMITED')throw e;}}
  if(failure)throw failure;return [];
}
async function getTrackStreamUrl(trackId,quality,context){
  const selected=Natune.unpack(trackId),lease=await accounts(context);
  if(!['tidal','qobuz'].includes(selected.service))throw Natune.error('INVALID_TRACK','Unknown leased provider');
  const account=lease.byService[selected.service].find(v=>v.id===selected.account);
  if(!account)throw Natune.error('VERIFY_REQUIRED','ArchivePool rotated this lease; search again');
  const q=selected.service==='tidal'?(quality==='hires'?'HI_RES_LOSSLESS':quality==='lossy'?'HIGH':'LOSSLESS'):(quality==='hires'?27:quality==='lossy'?5:6);
  const value=await (selected.service==='tidal'?PoolTidal:PoolQobuz).getTrackStreamUrl(selected.track,q,providerContext(selected.service,account,context));
  return Object.assign(value,{provider:'ArchivePool / '+selected.service,track:{sourceId:selected.track}});
}
module.exports={searchTracks,getTrackStreamUrl};
