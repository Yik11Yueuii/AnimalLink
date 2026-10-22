import type { AdoptionStatus, AnimalSex, AnimalSpecies, CurrentContext, SterilizationStatus } from '../types/api'

export const speciesLabel: Record<AnimalSpecies, string> = { CAT: '猫', DOG: '狗', OTHER: '其他' }
export const sexLabel: Record<AnimalSex, string> = { MALE: '公', FEMALE: '母', UNKNOWN: '未知' }
export const adoptionLabel: Record<AdoptionStatus, string> = {
  NOT_OPEN: '暂未开放领养',
  OPEN: '开放领养',
  HANDOVER_PENDING: '等待交接',
  ADOPTED: '已领养',
}
export const contextLabel: Record<CurrentContext, string> = { CAMPUS: '校园', ADOPTED_HOME: '领养家庭' }
export const sterilizationLabel: Record<SterilizationStatus, string> = {
  STERILIZED: '已绝育',
  NOT_STERILIZED: '未绝育',
  UNKNOWN: '未知',
}

export const formatDate = (value: string) => {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export const readableError = (error: unknown) => error instanceof Error
  ? error.message
  : '请求失败，请检查网络后重试。'
