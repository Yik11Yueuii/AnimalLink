import assert from 'node:assert/strict'
import test from 'node:test'
import { handoverLabel, localHandoverTime } from '../services/adoption'
import type { Application, Handover } from '../services/adoption'
const app={applicationId:'a',listingId:'l',status:'APPROVED',message:'',selected:true,reviewComment:null,createdAt:''} as Application
const handover=(status:Handover['status']):Handover=>({handoverId:'h',applicationId:'a',listingId:'l',status,scheduledAt:'2026-01-02T03:04:00Z',initiatedAt:'2026-01-01T00:00:00Z',completedAt:'2026-01-03T05:06:00Z'})
test('handover displays distinct results, failure and local times',()=>{
 assert.match(handoverLabel(app,null),/暂不可用/);assert.match(handoverLabel(app,[]),/未查询到交接安排/);assert.equal(handoverLabel({...app,selected:false},[]),'')
 assert.equal(handoverLabel(app,[handover('PENDING')]),'待交接 · 安排时间 '+localHandoverTime('2026-01-02T03:04:00Z'))
 assert.equal(handoverLabel(app,[handover('COMPLETED')]),'交接已完成 · 完成时间 '+localHandoverTime('2026-01-03T05:06:00Z'))
 assert.match(handoverLabel({...app,selected:false},[handover('CANCELLED')]),/交接已取消/);assert.match(handoverLabel(app,[handover('CANCELLED')]),/当前选定未查询到/)
 const d=new Date('2026-01-02T03:04:00Z');assert.equal(localHandoverTime('2026-01-02T03:04:00Z'),`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`)
})
