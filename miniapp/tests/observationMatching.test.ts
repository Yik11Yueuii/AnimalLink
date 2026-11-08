import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'
type Definition={data:Record<string,unknown>;[key:string]:unknown}
test('uncertain finalize queries decision and blocks another selection',async()=>{
 const g=globalThis as unknown as {wx?:Record<string,unknown>;Page?:(v:Definition)=>void},oldWx=g.wx,oldPage=g.Page,mode=runtimeConfig.userAuthenticationMode,base=runtimeConfig.gatewayBaseUrl
 const token='a'.repeat(43),campus={id:'campus',name:'c',shortName:null,city:'x',region:null,status:'ACTIVE'},calls:string[]=[];let definition:Definition|undefined
 g.Page=v=>{definition=v};g.wx={getStorageSync:(key:string)=>key==='animallink.miniapp.user-session'?token:campus,showModal:({success}:{success:(v:{confirm:boolean})=>void})=>success({confirm:true}),request:(o:{url:string;success:(v:{statusCode:number;data:unknown})=>void})=>{calls.push(o.url);if(o.url.endsWith('/finalize'))o.success({statusCode:503,data:{}});else o.success({statusCode:200,data:{state:'PENDING',matchingRecordId:'record',intent:{decisionType:'NO_MATCH',postText:'text'}}})}}
 runtimeConfig.userAuthenticationMode='api';runtimeConfig.gatewayBaseUrl='https://example.test'
 try{await import('../pages/observation/index');assert.ok(definition);const page=Object.assign({},definition,{generation:0,token,campusId:'campus',data:{...definition.data,task:{taskId:'task',confirmedAt:'yes'},matching:{matchingRecordId:'record',candidates:[]},postText:'text'},setData(value:Record<string,unknown>){Object.assign(this.data,value)}}) as unknown as Definition & {chooseDecision:(e:unknown)=>Promise<void>}
 await page.chooseDecision({currentTarget:{dataset:{kind:'NO_MATCH'}}});assert.equal(page.data.decisionLocked,true);assert.equal(page.data.statusChecked,true);assert.equal(calls.length,2);assert.match(calls[1],/\/decision\?/)
 await page.chooseDecision({currentTarget:{dataset:{kind:'UNSURE'}}});assert.equal(calls.length,2)
 }finally{g.wx=oldWx;g.Page=oldPage;runtimeConfig.userAuthenticationMode=mode;runtimeConfig.gatewayBaseUrl=base}
})
