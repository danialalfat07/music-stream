const CACHE='dnialify-v1.1.0';
const ASSETS=['/','/index.html','/styles.css','/app.js','/logo.png','/logo-192.png','/manifest.json'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const u=new URL(e.request.url);
  if(u.pathname.startsWith('/api/')) return;
  e.respondWith(caches.match(e.request).then(r=> r || fetch(e.request).then(res=>{
    if(res.ok && e.request.method==='GET' && u.origin===location.origin){
      const c=res.clone(); caches.open(CACHE).then(cache=>cache.put(e.request,c));
    }
    return res;
  }).catch(()=> r)));
});
