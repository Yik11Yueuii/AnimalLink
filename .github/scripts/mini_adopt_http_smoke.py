#!/usr/bin/env python3
"""Independent Gateway, Identity, Adoption and Animal JVMs with MySQL/MinIO; seeded sessions, mock provider."""
import base64, hashlib, hmac, json, os, secrets, subprocess, time, urllib.error, urllib.request, uuid
import boto3, pymysql
GATEWAY='http://127.0.0.1:18080'; IDENTITY='http://127.0.0.1:18081'; ANIMAL='http://127.0.0.1:18082'; ADOPTION='http://127.0.0.1:18084'
CAMPUS='22000000-0000-4000-8000-000000000001'; USERS=[str(uuid.uuid4()) for _ in range(3)]; TOKENS=[base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip('=') for _ in USERS]
assertion_key=os.urandom(32);keys='test='+base64.b64encode(assertion_key).decode(); access=secrets.token_hex(12); secret=secrets.token_hex(24); processes=[]; logs=[]
ADMIN=str(uuid.uuid4()); ADMIN_TOKEN=base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip('='); ORIGIN='https://admin.animallink.test'
def req(url,body=None,headers=None,method=None):
 data=None if body is None else json.dumps(body).encode(); q=urllib.request.Request(url,data=data,headers={'Content-Type':'application/json',**(headers or {})},method=method)
 try:
  with urllib.request.urlopen(q,timeout=45) as r:return r.status,r.read()
 except urllib.error.HTTPError as e:return e.code,e.read()
def check(label,status,result):
 code,data=result;print(label,code,flush=True)
 if code!=status:raise AssertionError(label+' '+str(code)+' '+data.decode(errors='replace')[:500])
 return json.loads(data) if data else {}
def sql(db,query,*args):
 with db.cursor() as c:c.execute(query,args);return c.fetchall()
def ready(url,process):
 for _ in range(150):
  if process.poll() is not None:raise RuntimeError('process exited '+url)
  try:
   code,body=req(url+'/actuator/health/readiness')
   if code==200 and json.loads(body)['status']=='UP':print('READY',url,'pid',process.pid,flush=True);return
  except (OSError,ValueError,KeyError):pass
  time.sleep(1)
 raise RuntimeError('readiness timeout '+url)
def main():
 db=pymysql.connect(host='127.0.0.1',user='root',password='',autocommit=True)
 try:
  for name in ('animallink_identity','animallink_animal','animallink_adoption'):sql(db,'CREATE DATABASE '+name+' CHARACTER SET utf8mb4')
  subprocess.run(['docker','run','-d','--name','mini-ai-minio','-p','9000:9000','-e','MINIO_ROOT_USER='+access,'-e','MINIO_ROOT_PASSWORD='+secret,'animallink-ai-smoke','server','/data'],check=True,stdout=subprocess.DEVNULL)
  storage=boto3.client('s3',endpoint_url='http://127.0.0.1:9000',aws_access_key_id=access,aws_secret_access_key=secret,region_name='us-east-1')
  for i in range(60):
   try:storage.create_bucket(Bucket='animallink-intelligence');break
   except Exception:
    if i==59:raise
    time.sleep(1)
  storage.create_bucket(Bucket='animallink-animal')
  env=os.environ.copy();env.update({'ANIMAL_DB_USER':'root','ANIMAL_DB_PASSWORD':'','ANIMAL_SERVICE_URL':ANIMAL,'ANIMAL_SERVICE_BASE_URL':ANIMAL,'ADOPTION_DB_USER':'root','ADOPTION_DB_PASSWORD':'','IDENTITY_SERVICE_BASE_URL':IDENTITY,'ANIMALLINK_MESSAGING_ENABLED':'false','ANIMALLINK_AUTHENTICATION_OBSERVATION_ASSERTION_KEY':base64.b64encode(os.urandom(32)).decode(),'IDENTITY_DB_USER':'root','IDENTITY_DB_PASSWORD':'','INTELLIGENCE_DB_USER':'root','INTELLIGENCE_DB_PASSWORD':'','AUTH_SERVICE_ASSERTION_CURRENT_KEY_ID':'test','AUTH_SERVICE_ASSERTION_KEYS':keys,'IDENTITY_INTERNAL_BASE_URL':'http://identity-service','IDENTITY_SERVICE_URL':IDENTITY,'MINIO_ACCESS_KEY':access,'MINIO_SECRET_KEY':secret,'CREDENTIAL_PRECHECK_DISPATCHER_ENABLED':'false','AI_PROVIDER':'mock'})
  common=['--spring.cloud.nacos.discovery.enabled=false','--spring.cloud.nacos.config.enabled=false','--spring.flyway.locations=classpath:db/migration','--spring.cloud.discovery.client.simple.instances.identity-service[0].uri='+IDENTITY,'--spring.cloud.discovery.client.simple.instances.animal-service[0].uri='+ANIMAL,'--spring.cloud.discovery.client.simple.instances.adoption-service[0].uri='+ADOPTION,'--management.endpoint.health.probes.enabled=true','--management.health.rabbit.enabled=false','--animallink.authentication.adoption-miniapp-assertion.keys='+keys,'--animallink.authentication.adoption-miniapp-assertion.current-key-id=test','--animallink.authentication.adoption-admin-assertion.keys='+keys,'--animallink.authentication.adoption-admin-assertion.current-key-id=test','--animallink.authentication.browser.allowed-origins='+ORIGIN]
  for name,port in [('identity-service',18081),('animal-service',18082),('adoption-service',18084),('api-gateway',18080)]:
   log=open(name+'-ai-smoke.log','w+',encoding='utf8');logs.append(log);args=['java','-jar','backend/'+name+'/target/'+name+'-0.1.0-SNAPSHOT.jar','--server.port='+str(port),*common];print('START',name,port,flush=True);processes.append(subprocess.Popen(args,env=env,stdout=log,stderr=subprocess.STDOUT))
  for url,p in zip((IDENTITY,ANIMAL,ADOPTION,GATEWAY),processes):ready(url,p)
  sql(db,'USE animallink_identity')
  sql(db,"INSERT INTO `user` (id,display_name,account_status,system_role) VALUES (%s,'admin','ACTIVE','GOVERNANCE_ADMIN')",ADMIN)
  admin_identity=str(uuid.uuid4());sql(db,"INSERT INTO external_identity(id,user_id,provider,provider_tenant,provider_subject) VALUES (%s,%s,'ADMIN_PASSWORD','','adopt.admin')",admin_identity,ADMIN)
  sql(db,"INSERT INTO admin_password_credential(external_identity_id,password_hash,password_algorithm,credential_version) VALUES (%s,'unused-test-password-hash','ARGON2ID_V1',1)",admin_identity)
  sql(db,"INSERT INTO admin_session(id,user_id,client_type,token_hash,credential_version,created_at,last_seen_at,idle_expires_at,absolute_expires_at) VALUES (%s,%s,'ADMIN_WEB',%s,1,NOW(6),NOW(6),DATE_ADD(NOW(6),INTERVAL 1 DAY),DATE_ADD(NOW(6),INTERVAL 2 DAY))",str(uuid.uuid4()),ADMIN,hashlib.sha256(ADMIN_TOKEN.encode()).digest())
  for i,user in enumerate(USERS):
   sql(db,"INSERT INTO `user` (id,display_name,account_status,system_role) VALUES (%s,'Smoke','ACTIVE','USER')",user)
   sql(db,"INSERT INTO wechat_user_session(id,user_id,token_hash,created_at,last_seen_at,idle_expires_at,absolute_expires_at) VALUES (%s,%s,%s,NOW(6),NOW(6),DATE_ADD(NOW(6),INTERVAL 1 DAY),DATE_ADD(NOW(6),INTERVAL 2 DAY))",str(uuid.uuid4()),user,hashlib.sha256(TOKENS[i].encode()).digest())
  verification=str(uuid.uuid4());sql(db,"INSERT INTO campus_verification(id,user_id,campus_id,requested_membership_type,applicant_name,status) VALUES (%s,%s,%s,'STUDENT','Smoke','APPROVED')",verification,USERS[0],CAMPUS)
  sql(db,"INSERT INTO campus_membership(id,user_id,campus_id,membership_type,status,approved_at,last_verification_id) VALUES (%s,%s,%s,'STUDENT','ACTIVE',NOW(6),%s)",str(uuid.uuid4()),USERS[0],CAMPUS,verification)
  verification2=str(uuid.uuid4());sql(db,"INSERT INTO campus_verification(id,user_id,campus_id,requested_membership_type,applicant_name,status) VALUES (%s,%s,%s,'STUDENT','Other','APPROVED')",verification2,USERS[2],CAMPUS)
  sql(db,"INSERT INTO campus_membership(id,user_id,campus_id,membership_type,status,approved_at,last_verification_id) VALUES (%s,%s,%s,'STUDENT','ACTIVE',NOW(6),%s)",str(uuid.uuid4()),USERS[2],CAMPUS,verification2)
  animal=str(uuid.uuid4());sql(db,'USE animallink_animal');sql(db,"INSERT INTO animal(id,campus_id,display_name,species) VALUES (%s,%s,'adoption smoke','CAT')",animal,CAMPUS)
  listing=str(uuid.uuid4());draft=str(uuid.uuid4());sql(db,'USE animallink_adoption')
  sql(db,"INSERT INTO adoption_listing(id,animal_id,status,title,description,publisher_user_id,created_at,updated_at,published_at) VALUES (%s,%s,'PUBLISHED','Available','Description',%s,NOW(6),NOW(6),NOW(6))",listing,animal,USERS[2])
  sql(db,"INSERT INTO adoption_listing(id,animal_id,status,title,description,publisher_user_id,created_at,updated_at) VALUES (%s,%s,'DRAFT','Private','Private',%s,NOW(6),NOW(6))",draft,str(uuid.uuid4()),USERS[2])
  public=GATEWAY+'/adoption/api/v1/public/listings';found=check('public listings',200,req(public));assert [x['listingId'] for x in found['items']]==[listing] and 'publisherUserId' not in found['items'][0]
  check('draft hidden',404,req(public+'/'+draft));check('published detail',200,req(public+'/'+listing))
  root=GATEWAY+'/auth/wechat/adoption';h=lambda i:{'X-Miniapp-Session':TOKENS[i]};submit=root+'/listings/'+listing+'/applications'
  check('no session',401,req(submit,{'message':'hello'}));check('nonmember',403,req(submit,{'message':'hello'},h(1)))
  path='/internal/v1/miniapp/adoption/applications';check('unsigned direct',401,req(ADOPTION+path,headers={'X-User-Id':USERS[0]}));check('invalid signature',401,req(ADOPTION+path,headers={'Authorization':'Bearer invalid'}))
  now=int(time.time());encode=lambda b:base64.urlsafe_b64encode(b).rstrip(b'=');header={'alg':'HS256','typ':'JWT','kid':'test'};claims={'iss':'api-gateway','sub':'api-gateway','aud':['adoption-service'],'target':'adoption-service','actor_id':USERS[0],'system_role':'USER','scope':['adoption.application.read'],'request_method':'GET','request_path':path,'iat':now,'exp':now+15,'jti':str(uuid.uuid4())};signing=encode(json.dumps(header,separators=(',',':')).encode())+b'.'+encode(json.dumps(claims,separators=(',',':')).encode());assertion=(signing+b'.'+encode(hmac.new(assertion_key,signing,hashlib.sha256).digest())).decode();signed={'Authorization':'Bearer '+assertion}
  check('valid signed request',200,req(ADOPTION+path,headers=signed));check('assertion replay',401,req(ADOPTION+path,headers=signed))
  app=check('eligible application',200,req(submit,{'message':'I can care'},h(0)));assert app['status']=='SUBMITTED';app_id=app['applicationId']
  check('duplicate application',409,req(submit,{'message':'again'},h(0)))
  mine=check('my applications',200,req(root+'/applications',headers=h(0)));assert [x['applicationId'] for x in mine['items']]==[app_id]
  check('other user detail',403,req(root+'/applications/'+app_id,headers=h(2)));check('other user withdraw',403,req(root+'/applications/'+app_id+'/withdraw',{},h(2)))
  withdrawn=check('owner withdraw',200,req(root+'/applications/'+app_id+'/withdraw',{},h(0)));assert withdrawn['status']=='WITHDRAWN';check('repeat withdraw',409,req(root+'/applications/'+app_id+'/withdraw',{},h(0)))
  assert sql(db,'SELECT status FROM adoption_application WHERE id=%s',app_id)[0][0]=='WITHDRAWN';assert sql(db,'SELECT COUNT(*) FROM adoption_application WHERE listing_id=%s',listing)[0][0]==1
  print('DB: one application, withdrawn',flush=True)
  handovers=root+'/handovers';check('handover no session',401,req(handovers));assert check('handover empty',200,req(handovers,headers=h(0)))==[]
  hp='/internal/v1/miniapp/adoption/handovers';check('handover unsigned',401,req(ADOPTION+hp));check('handover invalid',401,req(ADOPTION+hp,headers={'Authorization':'Bearer invalid'}))
  now=int(time.time());claims.update({'scope':['adoption.handover.read'],'request_path':hp,'iat':now,'exp':now+15,'jti':str(uuid.uuid4())});signing=encode(json.dumps(header,separators=(',',':')).encode())+b'.'+encode(json.dumps(claims,separators=(',',':')).encode());hsigned={'Authorization':'Bearer '+(signing+b'.'+encode(hmac.new(assertion_key,signing,hashlib.sha256).digest())).decode()}
  check('handover signed',200,req(ADOPTION+hp,headers=hsigned));check('handover replay',401,req(ADOPTION+hp,headers=hsigned))
  seeded={}
  for status,owner in [('PENDING',USERS[0]),('COMPLETED',USERS[0]),('CANCELLED',USERS[0]),('PENDING',USERS[2])]:
   lid,aid,sid,hid=[str(uuid.uuid4()) for _ in range(4)];seeded[hid]=(status,owner)
   sql(db,"INSERT INTO adoption_listing(id,animal_id,status,title,description,publisher_user_id,created_at,updated_at,published_at,closed_at) VALUES (%s,%s,'CLOSED','Fixture','Fixture',%s,NOW(6),NOW(6),NOW(6),NOW(6))",lid,str(uuid.uuid4()),USERS[2])
   sql(db,"INSERT INTO adoption_application(id,listing_id,applicant_user_id,status,message,created_at,updated_at,reviewer_user_id,reviewed_at) VALUES (%s,%s,%s,'APPROVED','Fixture',NOW(6),NOW(6),%s,NOW(6))",aid,lid,owner,USERS[2])
   if status=='CANCELLED':sql(db,"INSERT INTO adoption_selection(id,listing_id,application_id,status,selected_by_user_id,selected_at,cancelled_by_user_id,cancelled_at,cancel_reason) VALUES (%s,%s,%s,'CANCELLED',%s,NOW(6),%s,NOW(6),'fixture')",sid,lid,aid,USERS[2],USERS[2])
   else:sql(db,"INSERT INTO adoption_selection(id,listing_id,application_id,status,selected_by_user_id,selected_at) VALUES (%s,%s,%s,'ACTIVE',%s,NOW(6))",sid,lid,aid,USERS[2])
   if status=='COMPLETED':sql(db,"INSERT INTO adoption_handover(id,selection_id,status,scheduled_at,initiated_by_user_id,initiated_at,completed_by_user_id,completed_at,created_at,updated_at) VALUES (%s,%s,'COMPLETED',NOW(6),%s,NOW(6),%s,NOW(6),NOW(6),NOW(6))",hid,sid,USERS[2],USERS[2])
   elif status=='CANCELLED':sql(db,"INSERT INTO adoption_handover(id,selection_id,status,scheduled_at,initiated_by_user_id,initiated_at,cancelled_by_user_id,cancelled_at,cancel_reason,created_at,updated_at) VALUES (%s,%s,'CANCELLED',NOW(6),%s,NOW(6),%s,NOW(6),'fixture',NOW(6),NOW(6))",hid,sid,USERS[2],USERS[2])
   else:sql(db,"INSERT INTO adoption_handover(id,selection_id,status,scheduled_at,initiated_by_user_id,initiated_at,created_at,updated_at) VALUES (%s,%s,'PENDING',NOW(6),%s,NOW(6),NOW(6),NOW(6))",hid,sid,USERS[2])
  owner_rows=check('own handovers',200,req(handovers,headers=h(0)));other_rows=check('other handovers',200,req(handovers,headers=h(2)))
  assert {r['status'] for r in owner_rows}=={'PENDING','COMPLETED','CANCELLED'} and len(owner_rows)==3 and len(other_rows)==1
  assert all(seeded[r['handoverId']][1]==USERS[0] and not ({'applicantUserId','initiatedByUserId','note','cancelReason'} & r.keys()) for r in owner_rows)
  assert all(seeded[r['handoverId']][1]==USERS[2] for r in other_rows)
  now=int(time.time());claims.update({'iat':now,'exp':now+15,'jti':str(uuid.uuid4())});signing=encode(json.dumps(header,separators=(',',':')).encode())+b'.'+encode(json.dumps(claims,separators=(',',':')).encode());spoof={'Authorization':'Bearer '+(signing+b'.'+encode(hmac.new(assertion_key,signing,hashlib.sha256).digest())).decode(),'X-User-Id':USERS[2]};spoofed=check('spoofed user header ignored',200,req(ADOPTION+hp,headers=spoof));assert len(spoofed)==3 and all(seeded[r['handoverId']][1]==USERS[0] for r in spoofed)
  print('DB fixtures: handover PENDING/COMPLETED/CANCELLED; ownership verified',flush=True)
  animal2=str(uuid.uuid4());sql(db,'USE animallink_animal');sql(db,"INSERT INTO animal(id,campus_id,display_name,species) VALUES (%s,%s,'workflow animal','CAT')",animal2,CAMPUS)
  listing2=str(uuid.uuid4());sql(db,'USE animallink_adoption');sql(db,"INSERT INTO adoption_listing(id,animal_id,status,title,description,publisher_user_id,created_at,updated_at,published_at) VALUES (%s,%s,'PUBLISHED','Workflow','Description',%s,NOW(6),NOW(6),NOW(6))",listing2,animal2,USERS[1])
  first=check('workflow applicant one',200,req(root+'/listings/'+listing2+'/applications',{'message':'first'},h(0)))['applicationId']
  second=check('workflow applicant two',200,req(root+'/listings/'+listing2+'/applications',{'message':'second'},h(2)))['applicationId']
  with urllib.request.urlopen(GATEWAY+'/auth/admin/csrf',timeout=15) as response:
   csrf=json.loads(response.read());csrf_cookie=response.headers.get('Set-Cookie').split(';',1)[0]
  admin_headers={'Cookie':csrf_cookie+'; ANIMALLINK_ADMIN_SESSION='+ADMIN_TOKEN,'Origin':ORIGIN,'Sec-Fetch-Site':'same-origin',csrf['headerName']:csrf['token']}
  base=GATEWAY+'/adoption/api/v1/admin'
  check('admin review without session',401,req(base+'/applications/'+first+'/approve',{'comment':'no'},headers={'Origin':ORIGIN,'Sec-Fetch-Site':'same-origin',csrf['headerName']:csrf['token'],'Cookie':csrf_cookie}))
  missing_csrf=dict(admin_headers);missing_csrf.pop(csrf['headerName']);code,_=req(base+'/applications/'+first+'/approve',{'comment':'no'},missing_csrf);assert code==403
  check('unsigned direct admin',401,req(ADOPTION+'/api/v1/admin/applications/'+first+'/approve',{'comment':'no'}))
  check('admin review first',200,req(base+'/applications/'+first+'/approve',{'comment':'qualified'},admin_headers))
  check('admin review second',200,req(base+'/applications/'+second+'/approve',{'comment':'qualified'},admin_headers))
  selection=check('admin select first',200,req(base+'/listings/'+listing2+'/selection',{'applicationId':first},admin_headers))
  check('second selection rejected',409,req(base+'/listings/'+listing2+'/selection',{'applicationId':second},admin_headers))
  scheduled='2030-01-01T12:00:00+08:00'
  handover=check('admin initiate handover',200,req(base+'/selections/'+selection['selectionId']+'/handover',{'scheduledAt':scheduled},admin_headers))
  completed=check('admin complete handover',200,req(base+'/handovers/'+handover['handoverId']+'/complete',{},admin_headers))
  assert completed['status']=='COMPLETED'
  check('completed handover cannot cancel',409,req(base+'/handovers/'+handover['handoverId']+'/cancel',{'reason':'too late'},admin_headers))
  assert sql(db,'SELECT status FROM adoption_relation WHERE handover_id=%s',handover['handoverId'])[0][0]=='ACTIVE'
  assert sql(db,'SELECT selected_by_user_id FROM adoption_selection WHERE id=%s',selection['selectionId'])[0][0]==ADMIN
  assert sql(db,'SELECT completed_by_user_id FROM adoption_handover WHERE id=%s',handover['handoverId'])[0][0]==ADMIN
  assert any(row['applicationId']==first and row['status']=='COMPLETED' for row in check('applicant sees completion',200,req(handovers,headers=h(0))))
  print('DB: HTTP review, selection, handover and ACTIVE relation verified',flush=True)
  animal3=str(uuid.uuid4());sql(db,'USE animallink_animal');sql(db,"INSERT INTO animal(id,campus_id,display_name,species) VALUES (%s,%s,'cancel animal','CAT')",animal3,CAMPUS)
  listing3=str(uuid.uuid4());sql(db,'USE animallink_adoption');sql(db,"INSERT INTO adoption_listing(id,animal_id,status,title,description,publisher_user_id,created_at,updated_at,published_at) VALUES (%s,%s,'PUBLISHED','Cancel flow','Description',%s,NOW(6),NOW(6),NOW(6))",listing3,animal3,USERS[1])
  rejected=check('rejected applicant creates',200,req(root+'/listings/'+listing3+'/applications',{'message':'reject me'},h(2)))['applicationId']
  approved=check('cancel flow applicant creates',200,req(root+'/listings/'+listing3+'/applications',{'message':'approve me'},h(0)))['applicationId']
  check('admin rejects application',200,req(base+'/applications/'+rejected+'/reject',{'reason':'not suitable'},admin_headers))
  assert sql(db,'SELECT status FROM adoption_application WHERE id=%s',rejected)[0][0]=='REJECTED'
  check('cannot select rejected application',409,req(base+'/listings/'+listing3+'/selection',{'applicationId':rejected},admin_headers))
  check('admin approves cancel flow',200,req(base+'/applications/'+approved+'/approve',{'comment':'qualified'},admin_headers))
  selected3=check('admin selects cancel flow',200,req(base+'/listings/'+listing3+'/selection',{'applicationId':approved},admin_headers))
  pending3=check('admin initiates cancel flow',200,req(base+'/selections/'+selected3['selectionId']+'/handover',{'scheduledAt':scheduled},admin_headers))
  cancelled3=check('admin cancels pending handover',200,req(base+'/handovers/'+pending3['handoverId']+'/cancel',{'reason':'arrangement withdrawn'},admin_headers))
  assert cancelled3['status']=='CANCELLED'
  assert check('admin reads cancelled selection',200,req(base+'/listings/'+listing3+'/selection',headers=admin_headers))['status']=='CANCELLED'
  assert check('admin reads cancelled handover',200,req(base+'/selections/'+selected3['selectionId']+'/handover',headers=admin_headers))['status']=='CANCELLED'
  assert sql(db,'SELECT COUNT(*) FROM adoption_relation WHERE handover_id=%s',pending3['handoverId'])[0][0]==0
  print('DB: HTTP reject and cancelled handover verified; no relation for cancelled handover',flush=True)
 finally:
  for p in processes:
   if p.poll() is None:p.terminate()
  for p in processes:
   try:p.wait(timeout=15)
   except subprocess.TimeoutExpired:p.kill();p.wait()
  for log in logs:
   log.flush();log.seek(0);print('---',log.name,'last lines ---');print(''.join(log.readlines()[-50:]));log.close()
  db.close()
if __name__=='__main__':main()
