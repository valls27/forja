const V='forja-v1',A=['./','index.html','manifest.webmanifest','icon-192.png','icon-512.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(V).then(c=>c.addAll(A)));self.skipWaiting()});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!=V).map(x=>caches.delete(x)))));self.clients.claim()});
self.addEventListener('fetch',e=>{if(e.request.method!='GET')return;e.respondWith(caches.match(e.request,{ignoreSearch:true}).then(m=>{const n=fetch(e.request).then(r=>{if(r.ok||r.type=='opaque')caches.open(V).then(c=>c.put(e.request,r.clone()));return r}).catch(()=>m);return m||n}))});
