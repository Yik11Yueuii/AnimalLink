import { ApiRequestError, buildGatewayUrl } from './apiClient'
import { isSafeAuthenticationGatewayUrl, runtimeConfig } from '../config/runtime'
import { storedMiniappSession, clearMiniappSession } from './userAuthentication'
export interface Listing { listingId:string; animalId:string; title:string; description:string; publishedAt:string }
export interface Application { applicationId:string; listingId:string; status:'SUBMITTED'|'WITHDRAWN'|'APPROVED'|'REJECTED'; message:string; selected:boolean; reviewComment:string|null; createdAt:string }
export interface Page<T> { items:T[]; page:number; size:number; total:number }
export const apiEnabled=()=>runtimeConfig.publicDataMode==='api'&&isSafeAuthenticationGatewayUrl(runtimeConfig.gatewayBaseUrl)
export function adoptionRequest<T>(path:string,method:'GET'|'POST'='GET',body?:object,privateRequest=false):Promise<T>{
 const token=storedMiniappSession();if(!apiEnabled()||(privateRequest&&(runtimeConfig.userAuthenticationMode!=='api'||!token)))return Promise.reject(new ApiRequestError('请使用真实服务并登录。',401))
 return new Promise((resolve,reject)=>wx.request({url:buildGatewayUrl(runtimeConfig.gatewayBaseUrl,path),method,data:body,timeout:15000,header:{'Content-Type':'application/json',...(privateRequest?{'X-Miniapp-Session':token!}:{})},success:r=>{if(r.statusCode>=200&&r.statusCode<300){resolve(r.data as T);return}if(privateRequest&&r.statusCode===401&&token===storedMiniappSession())clearMiniappSession();reject(new ApiRequestError('服务返回 '+r.statusCode,r.statusCode))},fail:e=>reject(new ApiRequestError(e.errMsg,null))}))
}
export async function findApplication(listingId:string,valid:()=>boolean):Promise<Application|null>{let page=0;for(;;){const response=await adoptionRequest<Page<Application>>('/auth/wechat/adoption/applications?page='+page+'&size=100','GET',undefined,true);if(!valid())throw new Error('context changed');const found=response.items.find(a=>a.listingId===listingId&&(a.status==='SUBMITTED'||a.status==='APPROVED'));if(found)return found;if((page+1)*response.size>=response.total||response.items.length===0)return null;page++}}
export const statusLabel=(s:string)=>({SUBMITTED:'已提交，等待审核',WITHDRAWN:'已撤回',APPROVED:'审核通过，尚未完成交接',REJECTED:'审核未通过'} as Record<string,string>)[s]||s
