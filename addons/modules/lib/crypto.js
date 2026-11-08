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
