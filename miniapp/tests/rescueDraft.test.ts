import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'
import { createRescueDraft, getRescueDraft, submitRescueDraft, updateRescueDraft } from '../services/rescueDraft'

test('rescue draft API uses only the opaque miniapp session and exact paths', async () => {
  const beforeMode=runtimeConfig.userAuthenticationMode,beforeBase=runtimeConfig.gatewayBaseUrl
  const calls:Array<{path:string;method:string;data:unknown;session:string}> = []
  ;(globalThis as unknown as { wx: Record<string,unknown> }).wx={getStorageSync:()=> 'a'.repeat(43),request:(options:{url:string;method:string;data:unknown;header:Record<string,string>;success:(value:{statusCode:number;data:unknown})=>void})=>{calls.push({path:new URL(options.url).pathname,method:options.method,data:options.data,session:options.header['X-Miniapp-Session']});options.success({statusCode:200,data:{id:'23000000-0000-4000-8000-000000000010',status:'REPORTED'}})}}
  try { runtimeConfig.userAuthenticationMode='api';runtimeConfig.gatewayBaseUrl='https://localhost:8443';const input={campusId:'22000000-0000-4000-8000-000000000001',description:'发现动物异常',occurredAt:'2026-10-31T08:00:00.000Z',publicLocationDescription:'校园公共区域'};await createRescueDraft(input);await getRescueDraft('23000000-0000-4000-8000-000000000010');await updateRescueDraft('23000000-0000-4000-8000-000000000010',input);await submitRescueDraft('23000000-0000-4000-8000-000000000010');assert.deepEqual(calls.map(value=>[value.path,value.method,value.session]),[['/auth/wechat/rescue-drafts','POST','a'.repeat(43)],['/auth/wechat/rescue-drafts/23000000-0000-4000-8000-000000000010','GET','a'.repeat(43)],['/auth/wechat/rescue-drafts/23000000-0000-4000-8000-000000000010','PATCH','a'.repeat(43)],['/auth/wechat/rescue-drafts/23000000-0000-4000-8000-000000000010/submit','POST','a'.repeat(43)]]);assert.deepEqual(calls[0].data,input) } finally {runtimeConfig.userAuthenticationMode=beforeMode;runtimeConfig.gatewayBaseUrl=beforeBase}
})
