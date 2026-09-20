const CACHE='dnialify-assets-v2.3.38';
const ASSETS=['/','/index.html','/styles.css','/app.js','/logo.png','/logo-192.png','/manifest.json'];
self.addEventListener('message',e=>{
  if(e.data && e.data.type==='SKIP_WAITING') self.skipWaiting();
});
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE && k.startsWith('dnialify-')).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const u=new URL(e.request.url);
  if(u.pathname.startsWith('/api/')) return;
  // All versioned assets → network-first biar tidak stale 2.1.5 saat sudah 2.1.6 (HTML + JS/CSS)
  const isVersioned = e.request.mode==='navigate' || u.pathname==='/' || u.pathname.endsWith('.html') || u.pathname.endsWith('/app.js') || u.pathname.endsWith('/styles.css') || u.pathname.endsWith('/sw.js');
  if(isVersioned){
    e.respondWith(fetch(e.request,{cache:'no-store'}).then(res=>{
      if(res.ok && u.origin===location.origin){
        const c=res.clone(); caches.open(CACHE).then(cache=>cache.put(e.request,c));
      }
      return res;
    }).catch(()=> caches.match(e.request)));
    return;
  }
  e.respondWith(caches.match(e.request).then(r=> r || fetch(e.request).then(res=>{
    if(res.ok && e.request.method==='GET' && u.origin===location.origin){
      const c=res.clone(); caches.open(CACHE).then(cache=>cache.put(e.request,c));
    }
    return res;
  }).catch(()=> r)));
});
