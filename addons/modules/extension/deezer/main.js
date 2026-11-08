// Natune contributors, GPL-3.0-or-later. Plaintext instance protocol from DeezerInstanceResolver.
function toTrack(item) {
  const artists = (item.contributors || [item.artist]).map(v=>v?.name).filter(Boolean);
  if (item.id == null || !item.title || !artists.length) return null;
  return {id: String(item.id), title: item.title, artists, album: item.album?.title,
    duration: Number(item.duration) || undefined, explicit: !!item.explicit_lyrics, isrc: item.isrc};
}
async function searchTracks(query, limit) {
  const root = await Natune.json(Natune.query('https://api.deezer.com', '/search/track', {q:String(query),limit:String(Natune.limit(limit))}));
  if(root.error) throw Natune.error('REQUEST_FAILED','Deezer catalog rejected the search');
  return Natune.tracks(root).map(toTrack).filter(Boolean).slice(0,Natune.limit(limit));
}
async function getTrackStreamUrl(trackId, quality, context) {
  const base=Natune.base(context),headers={Range:'bytes=0-15'};
  const token=Natune.text(context,'instanceToken');if(token)headers.Authorization='Bearer '+token;
  const track=await Natune.json('https://api.deezer.com/track/'+encodeURIComponent(trackId));
  if(track.error)throw Natune.error('INVALID_TRACK','Deezer track is unavailable');
  const paths=[];if(track.isrc)paths.push('/stream/?isrc='+encodeURIComponent(track.isrc));paths.push('/stream/'+encodeURIComponent(trackId));
  let failure;
  for(const path of paths){try {
    const url=base+path,r=await Natune.request(url,{headers}),bytes=new Uint8Array(await r.arrayBuffer());
    // A server ignoring Range could exceed host memory bounds; require partial delivery.
    if(r.status!==206)throw Natune.error('UNSUPPORTED_AUDIO','Instance must honor bounded byte-range requests');
    const flac=bytes[0]===102&&bytes[1]===76&&bytes[2]===97&&bytes[3]===67;
    const mp3=bytes[0]===73&&bytes[1]===68&&bytes[2]===51||bytes[0]===255&&(bytes[1]&224)===224;
    if(!flac&&!mp3)throw Natune.error('UNSUPPORTED_AUDIO','Instance did not return plaintext FLAC or MP3');
    if(String(quality)==='FLAC'&&!flac)throw Natune.error('QUALITY_UNAVAILABLE','Instance returned MP3 for a FLAC request');
    const stream=Natune.audio(url,{mimeType:flac?'audio/flac':'audio/mpeg',codecs:flac?'flac':'mp3'});
    if(token)stream.headers={Authorization:'Bearer '+token};
    const total=Number(r.headers.get('Content-Range')?.split('/')[1]);if(total>0)stream.contentLength=total;
    return Object.assign(stream,{provider:'Deezer',track:{sourceId:String(trackId)},variant:flac?'flac':'mp3'});
  }catch(e){failure=e;if(['RATE_LIMITED','VERIFY_REQUIRED'].includes(e.code))throw e;}}
  throw failure||new Error('No Deezer instance stream');
}
module.exports={searchTracks,getTrackStreamUrl};
