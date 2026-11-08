import { getSelectedCampus } from '../../state/campus'
import { storedMiniappSession } from '../../services/userAuthentication'
import { observationRequest, uploadObservationImage } from '../../services/observation'
import type { DecisionState, Draft, Finalization, MatchDecisionInput, MatchRecord, ObservationTask } from '../../services/observation'
const empty = (): Draft => ({species:'UNKNOWN',sex:'UNKNOWN',coatColor:null,distinctiveFeatures:[],visibleCondition:null,behavior:null,estimatedCount:1,possibleAbnormality:false,abnormalFlags:[],locationDescription:null,occurredAt:null,confidence:0,fieldConfidence:{},warnings:[],unknownFields:[]})
Page({
 generation:0, token:'', campusId:'',
 data:{campusName:'',imagePath:'',text:'',location:'',task:null as ObservationTask|null,draft:null as Draft|null,draftJson:'',matching:null as MatchRecord|null,finalization:null as Finalization|null,decisionLocked:false,statusChecked:false,pending:null as MatchDecisionInput|null,postText:'',busy:false,error:'',message:'',speciesOptions:['UNKNOWN','CAT','DOG','OTHER'],sexOptions:['UNKNOWN','MALE','FEMALE'],speciesIndex:0,sexIndex:0},
 onShow(){const campus=getSelectedCampus();this.token=storedMiniappSession()||'';this.campusId=campus?.id||'';this.setData({campusName:campus?.name||''});if(!this.token||!this.campusId)this.setData({error:'请先登录并选择高校。'})},
 onHide(){this.clear()},onUnload(){this.clear()},
 clear(){this.generation++;this.setData({imagePath:'',text:'',location:'',task:null,draft:null,draftJson:'',matching:null,finalization:null,decisionLocked:false,statusChecked:false,pending:null,postText:'',error:'',message:'',busy:false})},
 current(v:number,t:string,c:string){return v===this.generation&&t===storedMiniappSession()&&c===getSelectedCampus()?.id},
 choose(){wx.chooseMedia({count:1,mediaType:['image'],success:r=>{const file=r.tempFiles[0];if(file)this.setData({imagePath:file.tempFilePath,error:''})}})},
 jsonChanged(e:WechatMiniprogram.Input){this.setData({draftJson:e.detail.value})},
 textChanged(e:WechatMiniprogram.Input){this.setData({text:e.detail.value})},locationChanged(e:WechatMiniprogram.Input){this.setData({location:e.detail.value})},
 colorChanged(e:WechatMiniprogram.Input){if(this.data.draft)this.setData({'draft.coatColor':e.detail.value})},behaviorChanged(e:WechatMiniprogram.Input){if(this.data.draft)this.setData({'draft.behavior':e.detail.value})},conditionChanged(e:WechatMiniprogram.Input){if(this.data.draft)this.setData({'draft.visibleCondition':e.detail.value})},
 speciesChanged(e:WechatMiniprogram.PickerChange){const i=Number(e.detail.value);this.setData({speciesIndex:i,'draft.species':this.data.speciesOptions[i]})},sexChanged(e:WechatMiniprogram.PickerChange){const i=Number(e.detail.value);this.setData({sexIndex:i,'draft.sex':this.data.sexOptions[i]})},
 async parse(){await this.start(false)},async manual(){await this.start(true)},
 async start(manual:boolean){if(this.data.busy)return;const t=this.token,c=this.campusId;if(!this.current(this.generation,t,c)||!t||!c||!this.data.imagePath){this.setData({error:'请登录、选择高校并选择真实图片。'});return}const v=++this.generation;this.setData({task:null,draft:null,draftJson:'',matching:null,finalization:null,decisionLocked:false,pending:null,statusChecked:false,postText:'',speciesIndex:0,sexIndex:0,busy:true,error:'',message:'正在上传图片并创建任务…'});try{const key=await uploadObservationImage(c,this.data.imagePath);if(!this.current(v,t,c))return;const draft=empty();draft.locationDescription=this.data.location||null;const body={campusId:c,mediaObjectKeys:[key],text:this.data.text,locationDescription:this.data.location||null,...(manual?{draft}:{})};const task=await observationRequest<ObservationTask>('/auth/wechat/observations/'+(manual?'manual':'parse'),'POST',body);if(!this.current(v,t,c))return;this.setData({task,draft:task.originalDraft,draftJson:JSON.stringify(task.originalDraft,null,2),speciesIndex:Math.max(0,this.data.speciesOptions.indexOf(task.originalDraft?.species||'')),sexIndex:Math.max(0,this.data.sexOptions.indexOf(task.originalDraft?.sex||'')),message:'请检查草稿并确认。'})}catch(e){if(this.current(v,t,c))this.setData({error:'上传或解析失败；请检查服务与图片，或选择手工录入。',message:''})}finally{if(v===this.generation)this.setData({busy:false})}},
 postChanged(e:WechatMiniprogram.Input){this.setData({postText:e.detail.value})},
 async decisionState(taskId:string,campus:string){return observationRequest<DecisionState>('/auth/wechat/observations/'+taskId+'/decision?campusId='+encodeURIComponent(campus),'GET')},
 async findMatches(){
  if(this.data.busy||!this.data.task?.confirmedAt||this.data.decisionLocked)return
  const v=this.generation,t=this.token,c=this.campusId,task=this.data.task.taskId
  if(!this.current(v,t,c)){this.clear();return}this.setData({busy:true,error:''})
  try {const state=await this.decisionState(task,c);if(!this.current(v,t,c))return
   if(state.state==='FINALIZED'){this.setData({finalization:state.result,decisionLocked:true});return}
   if(state.state==='PENDING'){this.setData({decisionLocked:true,pending:state.intent,statusChecked:true,error:'已有未完成的决定，请先查询状态并继续原决定。'});return}
   const match=await observationRequest<MatchRecord>('/auth/wechat/observations/'+task+'/match-candidates','POST',{campusId:c})
   if(!this.current(v,t,c))return
   if(match.campusId!==c||match.aiTaskId!==task)throw new Error('匹配结果不属于当前高校')
   const names:Record<string,string>={IMAGE:'外观',TRAIT:'特征',GEO:'地点',HISTORY:'历史观察'};const levels:Record<string,string>={HIGH:'较高',MEDIUM:'中等',LOW:'较低'}
   match.candidates=match.candidates.map(item=>({...item,scoreText:(item.finalScore*100).toFixed(1)+'%',reasonText:item.reasons.map(reason=>{if(reason==='NO_STRONG_MATCH')return '匹配较弱';const m=/^(IMAGE|TRAIT|GEO|HISTORY)_SIMILARITY_(HIGH|MEDIUM|LOW)$/.exec(reason);return m?names[m[1]]+'相似度'+levels[m[2]]:'其他匹配依据'}).join('、')||'暂无匹配依据'}))
   this.setData({matching:match,message:'请人工核对候选，分数仅供参考。'})
  }catch(e){if(this.current(v,t,c))this.setData({error:'匹配或状态查询失败，请稍后重试。'})}finally{if(v===this.generation)this.setData({busy:false})}
 },
 async chooseDecision(e:WechatMiniprogram.CustomEvent){
  if(this.data.busy||this.data.decisionLocked||!this.data.matching)return
  const kind=e.currentTarget.dataset.kind as MatchDecisionInput['decisionType'],selected=e.currentTarget.dataset.id as string|undefined,text=this.data.postText.trim()
  if(kind!=='UNSURE'&&!text){this.setData({error:'请填写动态内容。'});return}
  if(kind==='SELECT_EXISTING'&&!this.data.matching.candidates.some(x=>x.animalId===selected)){this.setData({error:'请选择本次候选。'});return}
  const v=this.generation,t=this.token,c=this.campusId
  const selectedName=this.data.matching.candidates.find(x=>x.animalId===selected)?.displayName||'所选动物'
  const labels={SELECT_EXISTING:'关联「'+selectedName+'」并发布动态',NO_MATCH:'发布动态并创建建档提案',UNSURE:'保存暂不确定，不发布动态'}
  const choice=await new Promise<boolean>(resolve=>wx.showModal({title:'确认人工决定',content:labels[kind],success:r=>resolve(r.confirm),fail:()=>resolve(false)}))
  if(!choice||!this.current(v,t,c)||this.data.busy||this.data.decisionLocked||!this.data.matching)return
  const input:MatchDecisionInput=kind==='UNSURE'?{decisionType:kind}:kind==='NO_MATCH'?{decisionType:kind,postText:text}:{decisionType:kind,selectedAnimalId:selected,postText:text}
  this.setData({decisionLocked:true,pending:input,statusChecked:false});await this.submitDecision(this.data.matching.matchingRecordId,input)
 },
 async submitDecision(id:string,input:MatchDecisionInput){
  if(this.data.busy||!this.data.task)return;const v=this.generation,t=this.token,c=this.campusId
  if(!this.current(v,t,c)){this.clear();return}this.setData({busy:true,error:''})
  try{const result=await observationRequest<Finalization>('/auth/wechat/matches/'+id+'/finalize','POST',{campusId:c,...input});if(this.current(v,t,c))this.setData({finalization:result,pending:null,message:'人工决定已保存。'})}
  catch(e){if(!this.current(v,t,c))return;try{const state=await this.decisionState(this.data.task.taskId,c);if(!this.current(v,t,c))return;if(state.state==='FINALIZED'){this.setData({finalization:state.result,pending:null,message:'已查询到完成的决定。'});return}this.setData({statusChecked:true,error:state.state==='PENDING'?'决定仍在处理，只能继续原决定。':'结果尚未确定；请查询状态后继续原决定，不能改选。'})}catch(err){if(this.current(v,t,c))this.setData({statusChecked:false,error:'结果未确定且状态查询失败；请先查询状态，不能改选。'})}}
  finally{if(v===this.generation)this.setData({busy:false})}
 },
 async checkDecision(){if(this.data.busy||!this.data.task)return;const v=this.generation,t=this.token,c=this.campusId;this.setData({busy:true,error:''});try{const state=await this.decisionState(this.data.task.taskId,c);if(!this.current(v,t,c))return;if(state.state==='FINALIZED')this.setData({finalization:state.result,pending:null,message:'已查询到完成的决定。'});else this.setData({pending:state.intent||this.data.pending,statusChecked:true,error:state.state==='PENDING'?'已有未完成决定，只能继续原决定。':'尚无完成记录，请仅继续原决定。'})}catch(e){if(this.current(v,t,c))this.setData({statusChecked:false,error:'状态查询失败，请稍后再查询。'})}finally{if(v===this.generation)this.setData({busy:false})}},
 async retryDecision(){if(!this.data.task||!this.data.pending||!this.data.statusChecked||this.data.busy)return;const v=this.generation,t=this.token,c=this.campusId;this.setData({busy:true,statusChecked:false});try{const state=await this.decisionState(this.data.task.taskId,c);if(!this.current(v,t,c))return;if(state.state==='FINALIZED'){this.setData({finalization:state.result,pending:null});return}const intent=state.intent||this.data.pending,id=state.matchingRecordId||this.data.matching?.matchingRecordId;if(!id||!intent)throw new Error('缺少原决定');this.setData({busy:false,pending:intent});await this.submitDecision(id,intent)}catch(e){if(this.current(v,t,c))this.setData({error:'状态查询失败，请稍后重试查询。'})}finally{if(v===this.generation)this.setData({busy:false})}},
 openPost(){const id=this.data.finalization?.createdPost?.id;if(id)wx.navigateTo({url:'/pages/post-detail/index?postId='+encodeURIComponent(id)})},
 async refresh(){if(!this.data.task||this.data.busy)return;const v=this.generation,t=this.token,c=this.campusId;this.setData({busy:true,error:''});try{const task=await observationRequest<ObservationTask>('/auth/wechat/observations/'+this.data.task.taskId,'GET');if(this.current(v,t,c))this.setData({task,message:task.confirmedAt?'观察草稿已确认。':'任务已刷新。'})}catch(e){if(this.current(v,t,c))this.setData({error:'查询失败，请稍后重试。'})}finally{if(v===this.generation)this.setData({busy:false})}},
 async confirm(){if(this.data.busy||!this.data.task||!this.data.draft)return;const v=this.generation,t=this.token,c=this.campusId;let advanced: Draft;try{advanced=JSON.parse(this.data.draftJson) as Draft}catch(e){this.setData({error:'结构化内容不是有效 JSON，请修正后确认。'});return}if(!this.current(v,t,c)){this.clear();return}this.setData({busy:true,error:''});try{const d=this.data.draft;const draft={...advanced,species:d.species,sex:d.sex,coatColor:d.coatColor,behavior:d.behavior,visibleCondition:d.visibleCondition};const task=await observationRequest<ObservationTask>('/auth/wechat/observations/'+this.data.task.taskId+'/confirm','POST',{draft});if(this.current(v,t,c))this.setData({task,message:'观察草稿已人工确认，尚未建立动物身份。'})}catch(e){if(this.current(v,t,c))this.setData({error:'确认结果未确定，请重新查询任务后再操作。'})}finally{if(v===this.generation)this.setData({busy:false})}},
})
