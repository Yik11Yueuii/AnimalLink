import assert from 'node:assert/strict'
import test from 'node:test'
import { buildGatewayUrl } from '../services/apiClient'

test('buildGatewayUrl encodes query parameters and removes the trailing slash', () => {
  assert.equal(
    buildGatewayUrl('https://gateway.example.test/', '/animal/api/v1/public/animals', {
      campusId: 'campus 明德', q: '橘 白', page: 0, size: 20,
    }),
    'https://gateway.example.test/animal/api/v1/public/animals?campusId=campus%20%E6%98%8E%E5%BE%B7&q=%E6%A9%98%20%E7%99%BD&page=0&size=20',
  )
})

test('buildGatewayUrl omits empty optional filters', () => {
  assert.equal(
    buildGatewayUrl('http://127.0.0.1:8080', '/animal/api/v1/public/animals', { campusId: 'c1', q: '', species: '', page: 0 }),
    'http://127.0.0.1:8080/animal/api/v1/public/animals?campusId=c1&page=0',
  )
})

test('buildGatewayUrl rejects non-http gateway schemes', () => {
  assert.throws(() => buildGatewayUrl('file:///tmp', '/animals'), /http:\/\/ 或 https:\/\//)
})
