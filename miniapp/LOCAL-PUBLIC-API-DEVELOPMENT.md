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
reads together. WeChat user authentication remains offline unless it is explicitly
enabled in the same develop-only override:

```js
wx.setStorageSync('animallink.runtime.public-data.v1', {
  publicDataMode: 'api',
  userAuthenticationMode: 'api',
  gatewayBaseUrl: '<LOCAL_GATEWAY_URL>'
})
```

Restart/recompile after changing the value. To keep real public reads but disable
login, omit `userAuthenticationMode` or set it to `offline-demo`. Login requires
the existing server-side WeChat configuration, HTTPS, and a permitted request
domain in the actual target environment; this local switch does not provide them.
For authentication, a non-loopback Gateway must use HTTPS. HTTP authentication
is accepted only for `localhost`, `127.0.0.1` or `[::1]` in a develop build;
other HTTP origins keep public API reads enabled but leave login offline.
If a saved session cannot be verified, retry it or use the explicit exit/switch
action before logging in again. A failed server revocation clears only the local
session and is reported as unconfirmed.
Never place an AppSecret or session token in this override.

To restore the tracked offline behavior:

```js
wx.removeStorageSync('animallink.runtime.public-data.v1')
```

Then restart/recompile. Trial and release builds ignore this storage key and
remain on the tracked safe default. API failures are shown as errors and never
fall back to offline fixtures.
