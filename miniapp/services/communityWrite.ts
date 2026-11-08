import { ApiRequestError, buildGatewayUrl } from './apiClient'
import { isSafeAuthenticationGatewayUrl, runtimeConfig } from '../config/runtime'
import { clearMiniappSession, storedMiniappSession } from './userAuthentication'
export interface CommunityLikeState { active: boolean; count: number }
export interface CommunityReceipt { id: string; reviewStatus: string }
export const communityWriteAvailable = () => runtimeConfig.userAuthenticationMode === 'api' && isSafeAuthenticationGatewayUrl(runtimeConfig.gatewayBaseUrl) && !!storedMiniappSession()
export function communityRequest<T>(path:string,method:'GET'|'POST'|'DELETE',data?:object):Promise<T> {
 const token=storedMiniappSession()
 if(!token||!communityWriteAvailable())return Promise.reject(new ApiRequestError('请先登录并启用真实服务。',401))
 return new Promise((resolve,reject)=>wx.request({url:buildGatewayUrl(runtimeConfig.gatewayBaseUrl,'/auth/wechat/community'+path),method,data,timeout:15000,header:{'Content-Type':'application/json','X-Miniapp-Session':token},success:r=>{if(r.statusCode>=200&&r.statusCode<300){resolve(r.data as T);return}if(r.statusCode===401&&storedMiniappSession()===token)clearMiniappSession();reject(new ApiRequestError('服务返回 '+r.statusCode,r.statusCode))},fail:e=>reject(new ApiRequestError(e.errMsg,null))}))
}
