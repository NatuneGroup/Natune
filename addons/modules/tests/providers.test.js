// CI/runtime regression fixtures; not executed on the constrained implementation VM.
// Run with: node --test addons/modules/tests/providers.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '..');
function load(relative, fetch) {
  const context = vm.createContext({module:{exports:{}},TextEncoder,TextDecoder,URL,URLSearchParams,Uint8Array,atob,btoa,fetch,Date,console});
  vm.runInContext(fs.readFileSync(path.join(root,relative),'utf8'),context);
  return context;
}
function settings(values) { return {settings:Object.fromEntries(Object.entries(values).map(([key,value])=>[key,{value}]))}; }
function response(status, data, headers={}) { return {status,ok:status>=200&&status<300,headers:new Headers(headers),json:async()=>data,text:async()=>JSON.stringify(data)}; }
test('Natune LRCLIB extension passes recording metadata and preserves synced LRC', async()=>{
  let request;
  const c=load('natune/lrclib/provider.js',async(url)=>{
    request=new URL(url);
    return response(200,{syncedLyrics:'[00:01.25]Hello',plainLyrics:'Hello'});
  });
  const result=await c.module.exports.getLyrics({title:'Song & title',artist:'Artist',album:'Album',duration:123},settings({}));
  assert.equal(request.hostname,'lrclib.net');
  assert.equal(request.searchParams.get('track_name'),'Song & title');
  assert.equal(request.searchParams.get('artist_name'),'Artist');
  assert.equal(request.searchParams.get('duration'),'123');
  assert.equal(result.format,'lrc');assert.equal(result.text,'[00:01.25]Hello');
});
test('Natune LRCLIB plain, missing and instrumental answers are distinct',async()=>{
  for (const [status,body,expected] of [[200,{plainLyrics:'Hello'},'Hello'],[404,{},null],[200,{instrumental:true,plainLyrics:'Instrumental'},null]]) {
    const c=load('natune/lrclib/provider.js',async()=>response(status,body));
    const result=await c.module.exports.getLyrics({title:'Song',artist:'Artist'},settings({}));
    if (expected===null) assert.equal(result,null);
    else {assert.equal(result.format,'plain');assert.equal(result.text,expected);}
  }
});
test('Natune LRCLIB errors do not become cacheable lyrics or request retries',async()=>{
  let calls=0;
  const c=load('natune/lrclib/provider.js',async()=>{calls++;return response(429,{});});
  await assert.rejects(c.module.exports.getLyrics({title:'Song',artist:'Artist'},settings({})),/LRCLIB request failed/);
  assert.equal(calls,1);
});
test('ArchivePool SHA-256 and AES-256-GCM match independently encrypted Node vectors; tampered tag fails',()=>{
  const c=load('lib/crypto.js'),api=vm.runInContext('PoolCrypto',c);
  const readKey='synthetic-fixture-key';
  const expected=crypto.createHash('sha256').update('archivepool-client:'+readKey).digest();
  assert.deepEqual(Buffer.from(api.sha256('archivepool-client:'+readKey)),expected);
  for(const length of [0,1,15,16,17,257]){
    const iv=Buffer.alloc(12,7),plain=Buffer.alloc(length,65),cipher=crypto.createCipheriv('aes-256-gcm',expected,iv);
    const sealed=Buffer.concat([cipher.update(plain),cipher.final(),cipher.getAuthTag()]);
    assert.deepEqual(Buffer.from(api.decrypt(expected,iv,sealed)),plain);
    const blob='enc:1:'+iv.toString('base64')+':'+sealed.toString('base64');
    assert.equal(api.field(blob,expected),plain.toString());
    sealed[sealed.length-1]^=1;
    assert.throws(()=>api.decrypt(expected,iv,sealed),/authentication failed/);
  }
});
test('TIDAL restriction 5003 tries hi-res, lossless, high, and returns actual encoding',async()=>{
  const tiers=[];
  const c=load('extension/tidal/provider.js',async(url)=>{
    const q=new URL(url).searchParams.get('audioquality');tiers.push(q);
    if(q!=='HIGH')return response(401,{subStatus:5003});
    return response(200,{manifestMimeType:'application/vnd.tidal.bts',manifest:Buffer.from(JSON.stringify({encryptionType:'NONE',urls:['https://audio.example/full.m4a'],mimeType:'audio/mp4',codecs:'mp4a.40.2'})).toString('base64'),audioQuality:'HIGH'});
  });
  const result=await c.module.exports.getTrackStreamUrl('123','HI_RES_LOSSLESS',settings({accessToken:'synthetic-opaque-token',countryCode:'US'}));
  assert.deepEqual(tiers,['HI_RES_LOSSLESS','LOSSLESS','HIGH']);assert.equal(result.bitrate,320000);
});
test('TIDAL 429 prevents subsequent requests, without retrying other quality tiers',async()=>{
  let calls=0;const c=load('extension/tidal/provider.js',async()=>{calls++;return response(429,{}, {'Retry-After':'60'});});
  const context=settings({accessToken:'synthetic',countryCode:'US'});
  await assert.rejects(c.module.exports.getTrackStreamUrl('123','HI_RES_LOSSLESS',context),e=>e.code==='RATE_LIMITED');
  await assert.rejects(c.module.exports.searchTracks('Song artist',10,context),e=>e.code==='RATE_LIMITED');assert.equal(calls,1);
});
test('Qobuz 429 does not retry a lower format or login',async()=>{
  let calls=0;const c=load('extension/qobuz/provider.js',async()=>{calls++;return response(429,{}, {'Retry-After':'60'});});
  const context=settings({appId:'12345',appSecret:'synthetic',userAuthToken:'synthetic'});
  await assert.rejects(c.module.exports.getTrackStreamUrl('123',27,context),e=>e.code==='RATE_LIMITED');
  await assert.rejects(c.module.exports.searchTracks('Song artist',10,context),e=>e.code==='RATE_LIMITED');assert.equal(calls,1);
});
