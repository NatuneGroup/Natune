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
module.exports={searchTracks,getTrackStreamUrl};
