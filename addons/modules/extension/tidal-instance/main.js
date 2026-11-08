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
