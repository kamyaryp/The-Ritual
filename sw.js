/* The Ritual - offline shell.
   The page is fetched from the network when there is one and falls back to the
   cache, so a new build is picked up on the next open without a version bump. */
var CACHE="ritual-v2";
var ASSETS=["./","./index.html","./manifest.webmanifest","./icon-192.png","./icon-512.png","./icon-maskable.png"];
self.addEventListener("install",function(e){
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE).then(function(c){ return c.addAll(ASSETS); }).catch(function(){}));
});
self.addEventListener("activate",function(e){
  e.waitUntil(caches.keys().then(function(ks){
    return Promise.all(ks.map(function(k){ return k===CACHE?null:caches.delete(k); }));
  }).then(function(){ return self.clients.claim(); }));
});
function isPage(req){
  if(req.mode==="navigate") return true;
  var u=new URL(req.url);
  return u.pathname.endsWith("/") || u.pathname.endsWith("index.html");
}
self.addEventListener("fetch",function(e){
  if(e.request.method!=="GET") return;
  if(isPage(e.request)){
    e.respondWith(fetch(e.request).then(function(res){
      var copy=res.clone();
      caches.open(CACHE).then(function(c){ c.put("./index.html",copy); }).catch(function(){});
      return res;
    }).catch(function(){
      return caches.match("./index.html").then(function(hit){ return hit || caches.match("./"); });
    }));
    return;
  }
  e.respondWith(caches.match(e.request).then(function(hit){
    return hit || fetch(e.request).then(function(res){
      var copy=res.clone();
      caches.open(CACHE).then(function(c){ c.put(e.request,copy); }).catch(function(){});
      return res;
    });
  }));
});
