import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'
import { isAnimalOfflineDemo, isCommunityOfflineDemo, isRescueOfflineDemo } from '../services/sourceFactory'

test('Animal API mode can be enabled without sending Community to protected APIs', () => {
  const previousAnimalMode = runtimeConfig.animalDataMode
  const previousCommunityMode = runtimeConfig.communityDataMode
  const previousRescueMode = runtimeConfig.rescueDataMode
  try {
    runtimeConfig.animalDataMode = 'api'
    runtimeConfig.communityDataMode = 'offline-demo'
    runtimeConfig.rescueDataMode = 'offline-demo'
    assert.equal(isAnimalOfflineDemo(), false)
    assert.equal(isCommunityOfflineDemo(), true)
    assert.equal(isRescueOfflineDemo(), true)
  } finally {
    runtimeConfig.animalDataMode = previousAnimalMode
    runtimeConfig.communityDataMode = previousCommunityMode
    runtimeConfig.rescueDataMode = previousRescueMode
  }
})
