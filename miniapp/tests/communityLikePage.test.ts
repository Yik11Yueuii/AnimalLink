import assert from 'node:assert/strict'
import test from 'node:test'
type Definition={data:Record<string,unknown>;[key:string]:unknown}
test('like state from another account is discarded before any write',async()=>{
 const g=globalThis as unknown as {wx?:Record<string,unknown>;Page?:(v:Definition)=>void},oldWx=g.wx,oldPage=g.Page;let definition:Definition|undefined,calls=0
 const campus={id:'campus',name:'Campus',shortName:null,city:'city',region:null,status:'ACTIVE'}
 g.wx={getStorageSync:(key:string)=>key==='animallink.miniapp.user-session'?'b'.repeat(43):campus,request:()=>{calls++}};g.Page=v=>{definition=v}
 try{await import('../pages/post-detail/index');assert.ok(definition);const page=Object.assign({},definition,{likeSession:'a'.repeat(43),likeCampus:'campus',data:{...definition.data,likeState:{active:true,count:1}},setData(value:Record<string,unknown>){Object.assign(this.data,value)}}) as unknown as Definition&{toggleLike:()=>Promise<void>};await page.toggleLike();assert.equal(page.data.likeState,null);assert.equal(calls,0)}finally{g.wx=oldWx;g.Page=oldPage}
})
