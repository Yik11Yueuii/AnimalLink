import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'
type Definition={data:Record<string,unknown>;[key:string]:unknown}
test('application list discards an old account response',async()=>{
 const g=globalThis as unknown as {wx?:Record<string,unknown>;Page?:(v:Definition)=>void},oldWx=g.wx,oldPage=g.Page,mode=runtimeConfig.publicDataMode,auth=runtimeConfig.userAuthenticationMode,base=runtimeConfig.gatewayBaseUrl;let definition:Definition|undefined,token='a'.repeat(43),reply:((v:{statusCode:number;data:unknown})=>void)|undefined
 g.Page=v=>{definition=v};g.wx={getStorageSync:()=>token,request:(o:{success:typeof reply})=>{reply=o.success}};runtimeConfig.publicDataMode='api';runtimeConfig.userAuthenticationMode='api';runtimeConfig.gatewayBaseUrl='https://example.test'
 try{await import('../pages/my-applications/index');assert.ok(definition);const page=Object.assign({},definition,{generation:0,session:token,data:{...definition.data},setData(value:Record<string,unknown>){Object.assign(this.data,value)}}) as unknown as Definition&{load:(reset:boolean)=>Promise<void>;onShow:()=>void};const pending=page.load(true);const oldReply=reply;token='b'.repeat(43);page.onShow();assert.deepEqual(page.data.items,[]);assert.ok(oldReply);oldReply({statusCode:200,data:{items:[{applicationId:'old',status:'SUBMITTED'}],page:0,size:20,total:1}});await pending;assert.deepEqual(page.data.items,[])}finally{g.wx=oldWx;g.Page=oldPage;runtimeConfig.publicDataMode=mode;runtimeConfig.userAuthenticationMode=auth;runtimeConfig.gatewayBaseUrl=base}
})
