import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'
import { uploadObservationImage } from '../services/observation'
test('uploads actual image bytes and sends no session to object storage', async () => {
 const root = globalThis as unknown as { wx?: Record<string, unknown> }, old = root.wx, mode = runtimeConfig.userAuthenticationMode, base = runtimeConfig.gatewayBaseUrl
 const calls: Array<{url:string;header:Record<string,string>;data:unknown}> = []
 root.wx = {getStorageSync:()=> 'a'.repeat(43), getFileSystemManager:()=>({readFile:({success}:{success:(v:{data:ArrayBuffer})=>void})=>success({data:new Uint8Array([255,216,255,1]).buffer})}), request:(o:{url:string;header:Record<string,string>;data:unknown;success:(v:{statusCode:number;data:unknown})=>void})=>{calls.push(o);o.success({statusCode:200,data:o.url.includes('upload-url')?{objectKey:'ai-input/user/file',uploadUrl:'https://storage.example.test/object'}:{}})}}
 runtimeConfig.userAuthenticationMode='api';runtimeConfig.gatewayBaseUrl='https://example.test'
 try {assert.equal(await uploadObservationImage('campus','image'), 'ai-input/user/file');assert.equal(calls.length,2);assert.equal(calls[0].header['X-Miniapp-Session'],'a'.repeat(43));assert.equal(calls[1].header['X-Miniapp-Session'],undefined);assert.equal(calls[1].header['Content-Type'],'image/jpeg');assert.ok(calls[1].data instanceof ArrayBuffer)} finally {root.wx=old;runtimeConfig.userAuthenticationMode=mode;runtimeConfig.gatewayBaseUrl=base}
})
