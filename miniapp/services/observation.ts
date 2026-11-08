import { ApiRequestError, buildGatewayUrl } from './apiClient'
import { isSafeAuthenticationGatewayUrl, runtimeConfig } from '../config/runtime'
import { storedMiniappSession, clearMiniappSession } from './userAuthentication'

export interface Draft { species: string; sex: string; coatColor: string | null; distinctiveFeatures: string[]; visibleCondition: string | null; behavior: string | null; estimatedCount: number; possibleAbnormality: boolean; abnormalFlags: string[]; locationDescription: string | null; occurredAt: string | null; confidence: number; fieldConfidence: Record<string, number>; warnings: string[]; unknownFields: string[] }
export interface ObservationTask { taskId: string; campusId: string; status: string; originalDraft: Draft | null; confirmedDraft: Draft | null; confirmedAt: string | null }
export const observationRequest = <T>(path: string, method: 'GET' | 'POST', data?: object): Promise<T> => new Promise((resolve,reject) => {
  const token = storedMiniappSession()
  if (!token) { reject(new ApiRequestError('请先登录。',401)); return }
  if (runtimeConfig.userAuthenticationMode !== 'api' || !isSafeAuthenticationGatewayUrl(runtimeConfig.gatewayBaseUrl)) { reject(new ApiRequestError('当前未配置真实服务。',null)); return }
  wx.request({url:buildGatewayUrl(runtimeConfig.gatewayBaseUrl,path),method,data,timeout:40000,header:{'Content-Type':'application/json','X-Miniapp-Session':token},success:r=>{if(r.statusCode>=200&&r.statusCode<300)resolve(r.data as T);else {if(r.statusCode===401&&storedMiniappSession()===token)clearMiniappSession();reject(new ApiRequestError(`服务返回 ${r.statusCode}`,r.statusCode))}},fail:e=>reject(new ApiRequestError(e.errMsg,null))})
})
export const uploadObservationImage = async (campusId: string, path: string) => {
  const intent = await observationRequest<{objectKey:string;uploadUrl:string}>('/auth/wechat/observations/upload-url','POST',{campusId})
  if (!/^https:\/\//i.test(intent.uploadUrl) && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?\//i.test(intent.uploadUrl)) throw new Error('上传地址不安全，请检查服务配置。')
  const bytes = await new Promise<ArrayBuffer>((resolve,reject)=>wx.getFileSystemManager().readFile({filePath:path,success:r=>resolve(r.data as ArrayBuffer),fail:reject}))
  if (!bytes.byteLength || bytes.byteLength > 10*1024*1024) throw new Error('图片需小于 10 MiB。')
  const b = new Uint8Array(bytes), type = b[0]===0xff && b[1]===0xd8 && b[2]===0xff ? 'image/jpeg' : b[0]===0x89 && b[1]===0x50 && b[2]===0x4e && b[3]===0x47 ? 'image/png' : b[0]===0x52 && b[1]===0x49 && b[2]===0x46 && b[3]===0x46 && b[8]===0x57 && b[9]===0x45 && b[10]===0x42 && b[11]===0x50 ? 'image/webp' : ''
  if (!type) throw new Error('仅支持 JPEG、PNG 或 WebP 图片。')
  await new Promise<void>((resolve,reject)=>wx.request({url:intent.uploadUrl,method:'PUT',data:bytes,header:{'Content-Type':type},success:r=>r.statusCode>=200&&r.statusCode<300?resolve():reject(new Error('图片上传失败。')),fail:reject}))
  return intent.objectKey
}
