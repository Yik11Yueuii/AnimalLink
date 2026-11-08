import assert from 'node:assert/strict'
import test from 'node:test'
import { runtimeConfig } from '../config/runtime'
type Definition={data:Record<string,unknown>;[key:string]:unknown}
test('like state is isolated from account changes and cleared on session expiry',async()=>{
 const g=globalThis as unknown as {wx?:Record<string,unknown>;Page?:(v:Definition)=>void},oldWx=g.wx,oldPage=g.Page,mode=runtimeConfig.userAuthenticationMode,base=runtimeConfig.gatewayBaseUrl;let definition:Definition|undefined,calls=0,token:string|undefined='b'.repeat(43)
 const campus={id:'campus',name:'Campus',shortName:null,city:'city',region:null,status:'ACTIVE'}
 g.wx={getStorageSync:(key:string)=>key==='animallink.miniapp.user-session'?token:campus,removeStorageSync:()=>{token=undefined},request:(o:{success:(r:{statusCode:number;data:object})=>void})=>{calls++;o.success({statusCode:401,data:{}})}};g.Page=v=>{definition=v};runtimeConfig.userAuthenticationMode='api';runtimeConfig.gatewayBaseUrl='https://example.test'
 try{await import('../pages/post-detail/index');assert.ok(definition);const page=Object.assign({},definition,{likeSession:'a'.repeat(43),likeCampus:'campus',data:{...definition.data,offline:false,postId:'post',likeState:{active:true,count:1},commentText:'private'},setData(value:Record<string,unknown>){Object.assign(this.data,value)}}) as unknown as Definition&{toggleLike:()=>Promise<void>};await page.toggleLike();assert.equal(page.data.likeState,null);assert.equal(calls,0)
 page.likeSession=token;page.data.likeState={active:true,count:1};await page.toggleLike();assert.equal(calls,1);assert.equal(page.data.likeState,null);assert.equal(page.data.likeBusy,false);assert.equal(page.data.commentText,'');assert.equal(page.data.actionMessage,'登录已失效，请重新登录。')
 }finally{g.wx=oldWx;g.Page=oldPage;runtimeConfig.userAuthenticationMode=mode;runtimeConfig.gatewayBaseUrl=base}
})
