# Local public API mode

The tracked default is the complete `offline-demo` experience. A missing local
configuration never breaks compilation.

For a WeChat **develop** build only, set this value from the Developer Tools
console before compiling or restarting the mini program:

```js
wx.setStorageSync('animallink.runtime.public-data.v1', {
  publicDataMode: 'api',
  gatewayBaseUrl: '<LOCAL_GATEWAY_URL>'
})
```

Replace the placeholder locally. Do not commit a workstation IP, credential, or
private URL. Restart/recompile after changing the value because runtime config
and all public DataSource singletons are intentionally created once at startup.

This one switch enables University/Campus, Animal, Community, and Rescue public
reads together. WeChat user authentication remains in its existing mode.

To restore the tracked offline behavior:

```js
wx.removeStorageSync('animallink.runtime.public-data.v1')
```

Then restart/recompile. Trial and release builds ignore this storage key and
remain on the tracked safe default. API failures are shown as errors and never
fall back to offline fixtures.
