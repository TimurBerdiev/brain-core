const prefix = `boss-shell:${self.registration.scope}:`;
const CACHE = `${prefix}2`;
const FILES = ['./', './index.html', './style.css', './app.js', './model.js', './github.js', './demo.js',
  './markdown.js', './dogfood.json', './vendor/js-yaml.mjs', './manifest.webmanifest', './icon.svg', './icon-180.png', './icon-192.png', './icon-512.png'];
const shell = new Set(FILES.map(path => new URL(path, self.registration.scope).href));

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(FILES)));
});

self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys
    .filter(key => key.startsWith(prefix) && key !== CACHE).map(key => caches.delete(key)))));
});

self.addEventListener('message', event => {
  if (event.data === 'boss-refresh-shell') event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(FILES)));
});

self.addEventListener('fetch', event => {
  const { request } = event;
  if (request.method !== 'GET' || new URL(request.url).origin !== self.location.origin) return;
  const url = new URL(request.url);
  if (request.mode === 'navigate' && shell.has(url.origin + url.pathname)) {
    event.respondWith(fetch(request).catch(() => caches.open(CACHE).then(cache => cache.match('./index.html'))));
  } else if (shell.has(request.url)) {
    event.respondWith(fetch(request).catch(() => caches.open(CACHE).then(cache => cache.match(request))));
  }
});
