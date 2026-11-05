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

test('trial and release builds ignore local storage API overrides', () => {
  const override = { publicDataMode: 'api' as const, gatewayBaseUrl: 'http://localhost:8080' }
  for (const environment of ['trial', 'release'] as const) {
    const config = resolveRuntimeConfig(environment, override)
    assert.equal(config.publicDataMode, 'offline-demo')
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
    { animalDataMode: 'api', gatewayBaseUrl: 'http://localhost:8080' },
  ]) assert.equal(resolveRuntimeConfig('develop', override).publicDataMode, 'offline-demo')
})
