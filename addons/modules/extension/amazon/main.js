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
