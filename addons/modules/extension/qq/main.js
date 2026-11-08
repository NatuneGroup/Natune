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
