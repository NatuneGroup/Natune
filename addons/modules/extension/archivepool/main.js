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
function services(context){
  const pinned=Natune.text(context,'service');
  if(pinned){if(!['tidal','qobuz'].includes(pinned))throw Natune.error('INVALID_TRACK','Unsupported leased service');return [pinned];}
  const preferred=Natune.setting(context,'source')==='qobuz'?['qobuz','tidal']:['tidal','qobuz'];
  const allowed=Natune.setting(context,'services');
  return Array.isArray(allowed)?preferred.filter(service=>allowed.includes(service)):preferred;
}
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
  if(!services(context).includes(selected.service))throw Natune.error('INVALID_TRACK','Unknown leased provider');
  const account=lease.byService[selected.service].find(v=>v.id===selected.account);
  if(!account)throw Natune.error('VERIFY_REQUIRED','ArchivePool rotated this lease; search again');
  const q=selected.service==='tidal'?(['HI_RES_LOSSLESS','LOSSLESS','HIGH','LOW'].includes(quality)?quality:quality==='hires'?'HI_RES_LOSSLESS':quality==='lossy'?'HIGH':'LOSSLESS'):([27,7,6,5].includes(Number(quality))?Number(quality):quality==='hires'?27:quality==='lossy'?5:6);
  const value=await (selected.service==='tidal'?PoolTidal:PoolQobuz).getTrackStreamUrl(selected.track,q,providerContext(selected.service,account,context));
  return Object.assign(value,{provider:'ArchivePool / '+selected.service,track:{sourceId:selected.track}});
}
module.exports={searchTracks,getTrackStreamUrl};
