import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'
type Definition={data:Record<string,unknown>;[key:string]:unknown}
test('new observation clears old result and ignores delayed confirmation',async()=>{
 const g=globalThis as unknown as {wx?:Record<string,unknown>;Page?:(v:Definition)=>void},oldWx=g.wx,oldPage=g.Page,mode=runtimeConfig.userAuthenticationMode,base=runtimeConfig.gatewayBaseUrl
 const token='a'.repeat(43),campus={id:'campus',name:'c',shortName:null,city:'x',region:null,status:'ACTIVE'},calls:string[]=[];let definition:Definition|undefined,confirmOld:((v:{confirm:boolean})=>void)|undefined
 const task={taskId:'B',confirmedAt:'yes',originalDraft:{species:'CAT',sex:'UNKNOWN'}}
 g.Page=v=>{definition=v};g.wx={getStorageSync:(key:string)=>key==='animallink.miniapp.user-session'?token:campus,showModal:({success}:{success:(v:{confirm:boolean})=>void})=>{confirmOld=success},getFileSystemManager:()=>({readFile:({success}:{success:(v:{data:ArrayBuffer})=>void})=>success({data:new Uint8Array([0x89,0x50,0x4e,0x47]).buffer})}),request:(o:{url:string;success:(v:{statusCode:number;data:unknown})=>void})=>{calls.push(o.url);let data:unknown={};if(o.url.endsWith('/upload-url'))data={objectKey:'key',uploadUrl:'https://example.test/upload'};else if(o.url.endsWith('/manual'))data=task;else if(o.url.includes('/decision?'))data={state:'NONE'};else if(o.url.endsWith('/match-candidates'))data={matchingRecordId:'record-B',aiTaskId:'B',campusId:'campus',candidates:[{animalId:'animal-B',displayName:'B',finalScore:0.5,reasons:[]}]};o.success({statusCode:200,data})}}
 runtimeConfig.userAuthenticationMode='api';runtimeConfig.gatewayBaseUrl='https://example.test'
 try{await import('../pages/observation/index');assert.ok(definition);const page=Object.assign({},definition,{generation:0,token,campusId:'campus',data:{...definition.data,imagePath:'image',task:{taskId:'A',confirmedAt:'yes'},matching:{matchingRecordId:'record-A',candidates:[{animalId:'animal-A',displayName:'A'}]},postText:'old'},setData(value:Record<string,unknown>){Object.assign(this.data,value)}}) as unknown as Definition & {chooseDecision:(e:unknown)=>Promise<void>;start:(manual:boolean)=>Promise<void>;findMatches:()=>Promise<void>}
 const stale=page.chooseDecision({currentTarget:{dataset:{kind:'NO_MATCH'}}});assert.ok(confirmOld)
 Object.assign(page.data,{finalization:{decision:{decisionType:'NO_MATCH'}},decisionLocked:true,pending:{decisionType:'NO_MATCH'},statusChecked:true})
 await page.start(true);assert.equal((page.data.task as {taskId:string}).taskId,'B');for(const key of ['matching','finalization','pending'])assert.equal(page.data[key],null);assert.equal(page.data.decisionLocked,false);assert.equal(page.data.statusChecked,false);assert.equal(page.data.postText,'')
 await page.findMatches();assert.equal((page.data.matching as {matchingRecordId:string}).matchingRecordId,'record-B');confirmOld({confirm:true});await stale;assert.equal(page.data.decisionLocked,false);assert.equal(page.data.finalization,null);assert.equal(calls.some(url=>url.endsWith('/finalize')),false)
 }finally{g.wx=oldWx;g.Page=oldPage;runtimeConfig.userAuthenticationMode=mode;runtimeConfig.gatewayBaseUrl=base}
})
