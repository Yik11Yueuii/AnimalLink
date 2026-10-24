import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'
import { isAnimalOfflineDemo, isCommunityOfflineDemo } from '../services/sourceFactory'

test('Animal API mode can be enabled without sending Community to protected APIs', () => {
  const previousAnimalMode = runtimeConfig.animalDataMode
  const previousCommunityMode = runtimeConfig.communityDataMode
  try {
    runtimeConfig.animalDataMode = 'api'
    runtimeConfig.communityDataMode = 'offline-demo'
    assert.equal(isAnimalOfflineDemo(), false)
    assert.equal(isCommunityOfflineDemo(), true)
  } finally {
    runtimeConfig.animalDataMode = previousAnimalMode
    runtimeConfig.communityDataMode = previousCommunityMode
  }
})
