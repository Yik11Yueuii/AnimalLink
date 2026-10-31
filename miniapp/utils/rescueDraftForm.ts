import type { RescueDraftInput } from '../services/rescueDraft'

export interface RescueDraftForm {
  description: string
  abnormalitySummary: string
  occurredAt: string
  publicLocationDescription: string
  exactLocationDescription: string
}

export const localDateTimeInput = (date = new Date()) => [
  date.getFullYear(),
  String(date.getMonth() + 1).padStart(2, '0'),
  String(date.getDate()).padStart(2, '0'),
].join('-') + 'T' + [
  String(date.getHours()).padStart(2, '0'),
  String(date.getMinutes()).padStart(2, '0'),
].join(':')

export const localDateTimeFromInstant = (instant: string) => localDateTimeInput(new Date(instant))

export const localDateTimeToInstant = (value: string) => {
  const matched = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value)
  if (!matched) throw new Error('发生时间格式无效。')
  const [, year, month, day, hour, minute] = matched
  const local = new Date(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute))
  if (localDateTimeInput(local) !== value) throw new Error('发生时间无效。')
  return local.toISOString()
}

export const rescueDraftFingerprint = (input: RescueDraftInput) => JSON.stringify({
  campusId: input.campusId,
  animalId: input.animalId || null,
  description: input.description,
  abnormalitySummary: input.abnormalitySummary || null,
  occurredAt: input.occurredAt,
  publicLocationDescription: input.publicLocationDescription,
  exactLocationDescription: input.exactLocationDescription || null,
})
