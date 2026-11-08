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

module.exports = {searchTracks, getTrackStreamUrl};
