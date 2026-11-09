/* Offline map tiles for the Fleet Tracker phone app.
 *
 * The APK serves its own pages, its library copies and its sounds, so the only
 * thing a handset cannot reach without a signal is the map picture. Every tile
 * that loads while the driver has internet is kept here, so an area he has driven
 * through once is still drawn the next time he opens the app in a dead spot.
 *
 * Nothing depends on this cache: a miss is simply a tile that does not arrive, and
 * the route line, the vehicle marker and the zones are drawn by the app itself, so
 * they are there either way.
 */
const TILES='fleet-tiles-v1', LIBS='fleet-libs-v1', TCAP=2000, LCAP=40;
// Google's tiles for the map, hybrid and satellite views, and Esri's numbered tile
// path (which has no file extension). Those two shapes cover every style the app
// offers now that OpenStreetMap is gone from the list.
const isTile=u=>/\/vt\/lyrs=/.test(u)||/\/tile\/\d+\/\d+\/\d+/.test(u);
// the CDN copies of the map library and the icon font, used only when the build
// fell back to a CDN instead of the files bundled in the APK
const isLib=u=>/^https:\/\/(cdnjs\.cloudflare\.com|cdn\.jsdelivr\.net)\//.test(u);
self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>
 Promise.all(k.filter(n=>n!==TILES&&n!==LIBS).map(n=>caches.delete(n)))).then(()=>self.clients.claim()))});
// cached first: a tile for a given zoom/col/row never changes, and answering from
// the cache is what makes the map appear with no network at all
// putting every tile means counting every tile, and keys() on a cache holding
// 2 000 entries costs real time on a handset - so the size is estimated and only
// checked after every 32nd new tile
const cnt={};
async function pick(req,cache,cap){
 const c=await caches.open(cache),hit=await c.match(req);
 if(hit)return hit;
 const res=await fetch(req);
 try{if(res.ok||res.type==='opaque'){await c.put(req,res.clone());
  if((cnt[cache]=(cnt[cache]||0)+1)%32===0){cnt[cache]=0;await trim(c,cap)}}}
 // a full or refused cache must never cost the driver the tile itself
 catch(e){}
 return res}
async function trim(c,cap){try{const k=await c.keys();if(k.length<=cap)return;
 for(const r of k.slice(0,Math.floor(k.length/2)))await c.delete(r)}catch(e){}}
self.addEventListener('fetch',e=>{const r=e.request;
 if(r.method!=='GET')return;
 if(isTile(r.url))e.respondWith(pick(r,TILES,TCAP));
 else if(isLib(r.url))e.respondWith(pick(r,LIBS,LCAP))});
