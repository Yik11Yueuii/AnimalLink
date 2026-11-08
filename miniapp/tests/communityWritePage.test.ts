import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'
type Definition={data:Record<string,unknown>;[key:string]:unknown}
test('community post does not repeat an uncertain write or display another account result',async()=>{
 const g=globalThis as unknown as {wx?:Record<string,unknown>;Page?:(v:Definition)=>void}, oldWx=g.wx,oldPage=g.Page,mode=runtimeConfig.userAuthenticationMode,base=runtimeConfig.gatewayBaseUrl
 const campus={id:'campus',name:'Campus',shortName:null,city:'city',region:null,status:'ACTIVE'},storage=new Map<string,unknown>([['animallink.miniapp.user-session','a'.repeat(43)],['animallink.selectedCampus.v1',campus]])
 let definition:Definition|undefined,calls=0,fail:((v:{errMsg:string})=>void)|undefined,success:((v:{statusCode:number;data:unknown})=>void)|undefined
 g.Page=v=>{definition=v};g.wx={getStorageSync:(key:string)=>storage.get(key),request:(o:{fail:typeof fail;success:typeof success})=>{calls++;fail=o.fail;success=o.success}}
 runtimeConfig.userAuthenticationMode='api';runtimeConfig.gatewayBaseUrl='https://example.test'
 try{await import('../pages/circle/index');assert.ok(definition);const page=Object.assign({},definition,{writeVersion:0,data:{...definition.data,offline:false,campus,writeText:'hello'},setData(value:Record<string,unknown>){Object.assign(this.data,value)}}) as unknown as Definition&{publish:()=>Promise<void>}
 const first=page.publish();assert.ok(fail);fail({errMsg:'network'});await first;assert.equal(page.data.writeUncertain,true);await page.publish();assert.equal(calls,1)
 page.data.writeUncertain=false;const second=page.publish();storage.set('animallink.miniapp.user-session','b'.repeat(43));assert.ok(success);success({statusCode:200,data:{id:'old',reviewStatus:'PENDING'}});await second;assert.notEqual(page.data.writeMessage,'已提交，待审核。')
 }finally{g.wx=oldWx;g.Page=oldPage;runtimeConfig.userAuthenticationMode=mode;runtimeConfig.gatewayBaseUrl=base}
})
