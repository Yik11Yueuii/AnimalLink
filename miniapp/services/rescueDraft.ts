import { ApiRequestError, buildGatewayUrl } from './apiClient'
import { runtimeConfig } from '../config/runtime'
import { storedMiniappSession } from './userAuthentication'

export interface RescueDraftInput { campusId: string; animalId?: string; description: string; abnormalitySummary?: string; occurredAt: string; publicLocationDescription: string; exactLocationDescription?: string }
export interface RescueDraft extends RescueDraftInput { id: string; finalEventId: string | null; finalizedAt: string | null; createdAt: string }
export interface SubmittedRescueEvent { id: string; status: 'REPORTED'; campusId: string }
const request = <T>(path:string, method:'GET'|'POST'|'PATCH', data?:object) => new Promise<T>((resolve,reject) => { const token=storedMiniappSession(); if(runtimeConfig.userAuthenticationMode!=='api'||!runtimeConfig.gatewayBaseUrl.trim()){reject(new ApiRequestError('当前为离线演示模式，救助上报需要真实登录。',null));return} if(!token){reject(new ApiRequestError('请先完成微信登录。',401));return} wx.request({url:buildGatewayUrl(runtimeConfig.gatewayBaseUrl,path),method:method as never,data,timeout:8000,header:{Accept:'application/json','Content-Type':'application/json','X-Miniapp-Session':token},success(response){if(response.statusCode>=200&&response.statusCode<300){resolve(response.data as T);return}reject(new ApiRequestError(`救助服务返回 ${response.statusCode}。`,response.statusCode))},fail(error){reject(new ApiRequestError(error.errMsg||'网络请求失败。',null))}}) })
export const createRescueDraft=(input:RescueDraftInput)=>request<RescueDraft>('/auth/wechat/rescue-drafts','POST',input)
export const getRescueDraft=(id:string)=>request<RescueDraft>(`/auth/wechat/rescue-drafts/${encodeURIComponent(id)}`,'GET')
export const updateRescueDraft=(id:string,input:RescueDraftInput)=>request<RescueDraft>(`/auth/wechat/rescue-drafts/${encodeURIComponent(id)}`,'PATCH',input)
export const submitRescueDraft=(id:string)=>request<SubmittedRescueEvent>(`/auth/wechat/rescue-drafts/${encodeURIComponent(id)}/submit`,'POST')
