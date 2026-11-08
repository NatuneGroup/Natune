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

// Natune contributors, GPL-3.0-or-later. QQ desktop RPC and entitled plaintext Vkey responses.
async function rpc(moduleName,method,param,context,auth=false){
  const uin=Natune.text(context,'uin'),key=Natune.text(context,'musicKey'),comm={ct:auth?24:19,cv:auth?0:2201};
  const headers={'Content-Type':'application/json',Referer:'https://y.qq.com/'};
  if(auth){if(!/^\d+$/.test(uin)||!key||/[\r\n;]/.test(key))throw Natune.error('VERIFY_REQUIRED','Configure your QQ account UIN and music key');Object.assign(comm,{format:'json',uin,authst:key,tmeLoginType:Number(Natune.setting(context,'loginType'))||1});headers.Cookie='uin='+uin+'; qqmusic_uin='+uin+'; qm_keyst='+key+'; qqmusic_key='+key;}
  const root=await Natune.json('https://u.y.qq.com/cgi-bin/musicu.fcg',{method:'POST',headers,body:JSON.stringify({comm,req_0:{module:moduleName,method,param}})});
  const sub=root.req_0||root.req;
  if(!sub)throw Natune.error('INVALID_RESPONSE','QQ returned no RPC response');
  if([1000,104400,104401].includes(Number(sub.code)))throw Natune.error('VERIFY_REQUIRED','QQ account session expired');
  if(Number(sub.code)!==0)throw Natune.error('REQUEST_FAILED','QQ refused the RPC request');
  return sub.data;
}
async function searchTracks(query,limit,context){
  const size=Natune.limit(limit),data=await rpc('music.search.SearchCgiService','DoSearchForQQMusicDesktop',{grp:1,num_per_page:size,page_num:1,query:String(query),search_type:0},context);
  const list=data?.body?.song?.list;if(!Array.isArray(list))throw Natune.error('INVALID_RESPONSE','QQ returned no search tracks');
  return list.map(v=>({id:Natune.opaque({mid:v.mid||v.songmid,mediaMid:v.file?.media_mid||v.media_mid,type:v.type||0}),title:v.title||v.name||v.songname,artists:(v.singer||[]).map(a=>a.name).filter(Boolean),album:v.album?.name||v.albumname,duration:Number(v.interval)||undefined,isrc:v.isrc})).filter(t=>t.title&&t.artists.length).slice(0,size);
}
async function getTrackStreamUrl(trackId,quality,context){
  const t=Natune.unpack(trackId),uin=Natune.text(context,'uin'),guid=String(Math.floor(Math.random()*9000000000)+1000000000),media=t.mediaMid||t.mid+t.mid;
  const tier=quality==='STANDARD'?['M500','.mp3']:quality==='HIGH'?['M800','.mp3']:['F000','.flac'];
  let failure;
  for(const [moduleName,method] of [['music.vkey.GetVkey','UrlGetVkey'],['music.vkey.GetEVkey','CgiGetEVkey'],['vkey.GetVkeyServer','CgiGetVkey']]){try{
    const data=await rpc(moduleName,method,{uin,guid,ctx:0,loginflag:1,platform:'20',songmid:[t.mid],filename:[tier[0]+media+tier[1]],songtype:[t.type]},context,true);
    const entry=data?.midurlinfo?.[0],path=entry?.purl;if(!path)continue;
    if(entry.ekey||/\.(?:mflac\w*|mgg\w*|mmp4|mnac)(?:[?#]|$)/i.test(path))throw Natune.error('UNSUPPORTED_AUDIO','QQ returned encrypted QMC audio; this module accepts plaintext or HLS only');
    const cdn=(data.sip||[]).find(v=>/^https:\/\//.test(v)&&!/^https:\/\/ws/.test(v))||'https://isure.stream.qqmusic.qq.com/';
    const url=/^https?:\/\//.test(path)?path:cdn.replace(/\/$/,'')+'/'+path.replace(/^\//,'');
    return Object.assign(Natune.audio(url,{mimeType:/\.m3u8(?:[?#]|$)/i.test(path)?'application/x-mpegURL':tier[1]==='.flac'?'audio/flac':'audio/mpeg'}),{provider:'QQ Music',track:{sourceId:t.mid},variant:tier[0]});
  }catch(e){failure=e;if(['RATE_LIMITED','VERIFY_REQUIRED','UNSUPPORTED_AUDIO'].includes(e.code))throw e;}}
  throw failure||Natune.error('UNSUPPORTED_AUDIO','QQ returned no entitled plaintext stream');
}
module.exports={searchTracks,getTrackStreamUrl};
