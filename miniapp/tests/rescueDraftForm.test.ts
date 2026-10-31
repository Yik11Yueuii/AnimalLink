import assert from 'node:assert/strict'
import test from 'node:test'
import {
  localDateTimeFromInstant,
  localDateTimeInput,
  localDateTimeToInstant,
  rescueDraftFingerprint,
} from '../utils/rescueDraftForm'

test('rescue occurrence time round-trips local Asia/Shanghai form values without UTC drift', () => {
  const local = localDateTimeFromInstant('2026-10-31T00:00:00.000Z')
  assert.equal(local, '2026-10-31T08:00')
  assert.equal(localDateTimeToInstant(local), '2026-10-31T00:00:00.000Z')
  assert.equal(localDateTimeInput(new Date('2026-10-31T00:00:00.000Z')), local)
})

test('rescue draft fingerprint changes when an unsaved form field changes', () => {
  const saved = {
    campusId: '22000000-0000-4000-8000-000000000001',
    description: '发现异常',
    occurredAt: '2026-10-31T00:00:00.000Z',
    publicLocationDescription: '校园公共区域',
  }
  assert.notEqual(rescueDraftFingerprint(saved), rescueDraftFingerprint({ ...saved, description: '更新后的异常描述' }))
})
