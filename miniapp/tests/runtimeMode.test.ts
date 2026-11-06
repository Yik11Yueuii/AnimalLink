import assert from 'node:assert/strict'
import test from 'node:test'
import { resolveRuntimeConfig, runtimeConfig } from '../config/runtime'
import { ApiPublicAnimalDataSource } from '../services/dataSource'
import { ApiCommunityDataSource } from '../services/communityDataSource'
import { ApiRescueDataSource } from '../services/rescueDataSource'
import { OfflinePublicAnimalDataSource } from '../services/offlineDataSource'
import { OfflineCommunityDataSource } from '../services/offlineCommunityDataSource'
import { OfflineRescueDataSource } from '../services/offlineRescueDataSource'
import { createPublicDataSources, isAnimalOfflineDemo, isCommunityOfflineDemo, isRescueOfflineDemo } from '../services/sourceFactory'

test('tracked defaults remain a complete offline demo without a local override file', () => {
  assert.equal(runtimeConfig.publicDataMode, 'offline-demo')
  assert.equal(runtimeConfig.userAuthenticationMode, 'offline-demo')
  assert.equal(isAnimalOfflineDemo(), true)
  assert.equal(isCommunityOfflineDemo(), true)
  assert.equal(isRescueOfflineDemo(), true)
})

test('develop override enables Animal, Community and Rescue as one API mode while login stays offline', () => {
  const config = resolveRuntimeConfig('develop', {
    publicDataMode: 'api', gatewayBaseUrl: 'http://127.0.0.1:8080/',
  })
  assert.deepEqual(config, {
    publicDataMode: 'api', animalDataMode: 'api', communityDataMode: 'api', rescueDataMode: 'api',
    userAuthenticationMode: 'offline-demo', gatewayBaseUrl: 'http://127.0.0.1:8080',
  })
  const sources = createPublicDataSources(config, () => async <T>() => ({} as T))
  assert.ok(sources.animal instanceof ApiPublicAnimalDataSource)
  assert.ok(sources.community instanceof ApiCommunityDataSource)
  assert.ok(sources.rescue instanceof ApiRescueDataSource)
})

test('develop enables WeChat authentication only with an explicit API opt-in and real public data', () => {
  const base = { publicDataMode: 'api', gatewayBaseUrl: 'https://example.test/' }
  assert.equal(resolveRuntimeConfig('develop', base).userAuthenticationMode, 'offline-demo')
  assert.equal(resolveRuntimeConfig('develop', { ...base, userAuthenticationMode: 'offline-demo' }).userAuthenticationMode, 'offline-demo')
  const enabled = resolveRuntimeConfig('develop', { ...base, userAuthenticationMode: 'api' })
  assert.equal(enabled.userAuthenticationMode, 'api')
  assert.equal(enabled.publicDataMode, 'api')
  assert.equal(enabled.gatewayBaseUrl, 'https://example.test')
  assert.equal(resolveRuntimeConfig('develop', {
    publicDataMode: 'offline-demo', gatewayBaseUrl: '', userAuthenticationMode: 'api',
  }).userAuthenticationMode, 'offline-demo')
})

test('authentication accepts HTTPS and only explicit loopback HTTP origins', () => {
  const auth = (gatewayBaseUrl: string) => resolveRuntimeConfig('develop', {
    publicDataMode: 'api', userAuthenticationMode: 'api', gatewayBaseUrl,
  })
  for (const url of ['https://example.test', 'http://localhost:8080', 'http://127.0.0.1:8080', 'http://[::1]:8080']) {
    const config = auth(url)
    assert.equal(config.userAuthenticationMode, 'api', url)
    assert.equal(config.publicDataMode, 'api', url)
  }
  for (const url of ['http://192.168.1.8:8080', 'http://10.0.0.3:8080', 'http://example.test',
    'http://127.1:8080', 'http://2130706433:8080', 'http://%6cocalhost:8080']) {
    const config = auth(url)
    assert.equal(config.userAuthenticationMode, 'offline-demo', url)
    assert.equal(config.publicDataMode, 'api', url)
    assert.ok(createPublicDataSources(config).animal instanceof ApiPublicAnimalDataSource)
  }
})

test('a runtime without global URL still parses the restricted origin grammar and keeps IPv6 loopback', () => {
  const global = globalThis as unknown as { URL?: typeof URL }
  const previous = global.URL
  try {
    global.URL = undefined
    const local = resolveRuntimeConfig('develop', {
      publicDataMode: 'api', userAuthenticationMode: 'api', gatewayBaseUrl: 'http://[::1]:8080',
    })
    assert.equal(local.userAuthenticationMode, 'api')
    const remote = resolveRuntimeConfig('develop', {
      publicDataMode: 'api', userAuthenticationMode: 'api', gatewayBaseUrl: 'http://example.test',
    })
    assert.equal(remote.publicDataMode, 'api')
    assert.equal(remote.userAuthenticationMode, 'offline-demo')
  } finally { global.URL = previous }
})

test('trial, release and unknown builds ignore local storage API and authentication overrides', () => {
  const override = { publicDataMode: 'api' as const, userAuthenticationMode: 'api' as const, gatewayBaseUrl: 'http://localhost:8080' }
  for (const environment of ['trial', 'release', 'unknown'] as const) {
    const config = resolveRuntimeConfig(environment, override)
    assert.equal(config.publicDataMode, 'offline-demo')
    assert.equal(config.userAuthenticationMode, 'offline-demo')
    assert.equal(config.gatewayBaseUrl, '')
    const sources = createPublicDataSources(config)
    assert.ok(sources.animal instanceof OfflinePublicAnimalDataSource)
    assert.ok(sources.community instanceof OfflineCommunityDataSource)
    assert.ok(sources.rescue instanceof OfflineRescueDataSource)
  }
})

test('invalid or partial develop overrides fail closed to the offline demo', () => {
  for (const override of [
    { publicDataMode: 'api', gatewayBaseUrl: '' },
    { publicDataMode: 'api', gatewayBaseUrl: 'javascript:alert(1)' },
    { publicDataMode: 'api', gatewayBaseUrl: 'http://user:secret@localhost:8080' },
    { publicDataMode: 'api', gatewayBaseUrl: 'https://user:secret@example.test' },
    { publicDataMode: 'api', gatewayBaseUrl: 'https://example.test/private', userAuthenticationMode: 'api' },
    { publicDataMode: 'api', gatewayBaseUrl: 'https://example.test/?token=x', userAuthenticationMode: 'api' },
    { publicDataMode: 'api', gatewayBaseUrl: 'http://localhost\\@example.test', userAuthenticationMode: 'api' },
    { publicDataMode: 'api', gatewayBaseUrl: 'http://localhost:99999', userAuthenticationMode: 'api' },
    { publicDataMode: 'api', gatewayBaseUrl: 'http://localhost:abc', userAuthenticationMode: 'api' },
    { publicDataMode: 'api', gatewayBaseUrl: 'https://-bad.example', userAuthenticationMode: 'api' },
    { publicDataMode: 'api', gatewayBaseUrl: 'https://example.test', userAuthenticationMode: 'ADMIN' },
    { publicDataMode: 'offline-demo', gatewayBaseUrl: '', userAuthenticationMode: 'api' },
    { animalDataMode: 'api', gatewayBaseUrl: 'http://localhost:8080' },
  ]) {
    const config = resolveRuntimeConfig('develop', override)
    assert.equal(config.publicDataMode, 'offline-demo')
    assert.equal(config.userAuthenticationMode, 'offline-demo')
  }
})
