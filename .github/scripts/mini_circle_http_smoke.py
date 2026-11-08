#!/usr/bin/env python3
"""Independent Gateway, Identity and Animal JVMs with MySQL/MinIO; seeded sessions, mock provider."""
import base64, hashlib, hmac, json, os, secrets, subprocess, time, urllib.error, urllib.request, uuid
import boto3, pymysql
GATEWAY='http://127.0.0.1:18080'; IDENTITY='http://127.0.0.1:18081'; ANIMAL='http://127.0.0.1:18082'; AI='http://127.0.0.1:18085'
CAMPUS='22000000-0000-4000-8000-000000000001'; USERS=[str(uuid.uuid4()) for _ in range(2)]; TOKENS=[base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip('=') for _ in USERS]
assertion_key=os.urandom(32);keys='test='+base64.b64encode(assertion_key).decode(); access=secrets.token_hex(12); secret=secrets.token_hex(24); processes=[]; logs=[]
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
  for name in ('animallink_identity','animallink_animal'):sql(db,'CREATE DATABASE '+name+' CHARACTER SET utf8mb4')
  subprocess.run(['docker','run','-d','--name','mini-ai-minio','-p','9000:9000','-e','MINIO_ROOT_USER='+access,'-e','MINIO_ROOT_PASSWORD='+secret,'animallink-ai-smoke','server','/data'],check=True,stdout=subprocess.DEVNULL)
  storage=boto3.client('s3',endpoint_url='http://127.0.0.1:9000',aws_access_key_id=access,aws_secret_access_key=secret,region_name='us-east-1')
  for i in range(60):
   try:storage.create_bucket(Bucket='animallink-intelligence');break
   except Exception:
    if i==59:raise
    time.sleep(1)
  storage.create_bucket(Bucket='animallink-animal')
  env=os.environ.copy();env.update({'ANIMAL_DB_USER':'root','ANIMAL_DB_PASSWORD':'','ANIMAL_SERVICE_URL':ANIMAL,'IDENTITY_SERVICE_BASE_URL':IDENTITY,'ANIMALLINK_MESSAGING_ENABLED':'false','ANIMALLINK_AUTHENTICATION_OBSERVATION_ASSERTION_KEY':base64.b64encode(os.urandom(32)).decode(),'IDENTITY_DB_USER':'root','IDENTITY_DB_PASSWORD':'','INTELLIGENCE_DB_USER':'root','INTELLIGENCE_DB_PASSWORD':'','AUTH_SERVICE_ASSERTION_CURRENT_KEY_ID':'test','AUTH_SERVICE_ASSERTION_KEYS':keys,'IDENTITY_INTERNAL_BASE_URL':'http://identity-service','IDENTITY_SERVICE_URL':IDENTITY,'ANIMAL_MINIAPP_BASE_URL':ANIMAL,'MINIO_ACCESS_KEY':access,'MINIO_SECRET_KEY':secret,'CREDENTIAL_PRECHECK_DISPATCHER_ENABLED':'false','AI_PROVIDER':'mock'})
  common=['--spring.cloud.nacos.discovery.enabled=false','--spring.cloud.nacos.config.enabled=false','--spring.flyway.locations=classpath:db/migration','--spring.cloud.discovery.client.simple.instances.identity-service[0].uri='+IDENTITY,'--spring.cloud.discovery.client.simple.instances.animal-service[0].uri='+ANIMAL,'--management.endpoint.health.probes.enabled=true','--management.health.rabbit.enabled=false','--animallink.authentication.animal-miniapp-assertion.keys='+keys,'--animallink.authentication.animal-miniapp-assertion.current-key-id=test']
  for name,port in [('identity-service',18081),('animal-service',18082),('api-gateway',18080)]:
   log=open(name+'-ai-smoke.log','w+',encoding='utf8');logs.append(log);args=['java','-jar','backend/'+name+'/target/'+name+'-0.1.0-SNAPSHOT.jar','--server.port='+str(port),*common];print('START',name,port,flush=True);processes.append(subprocess.Popen(args,env=env,stdout=log,stderr=subprocess.STDOUT))
  for url,p in zip((IDENTITY,ANIMAL,GATEWAY),processes):ready(url,p)
  sql(db,'USE animallink_identity')
  for i,user in enumerate(USERS):
   sql(db,"INSERT INTO `user` (id,display_name,account_status,system_role) VALUES (%s,'Smoke','ACTIVE','USER')",user)
   sql(db,"INSERT INTO wechat_user_session(id,user_id,token_hash,created_at,last_seen_at,idle_expires_at,absolute_expires_at) VALUES (%s,%s,%s,NOW(6),NOW(6),DATE_ADD(NOW(6),INTERVAL 1 DAY),DATE_ADD(NOW(6),INTERVAL 2 DAY))",str(uuid.uuid4()),user,hashlib.sha256(TOKENS[i].encode()).digest())
  verification=str(uuid.uuid4());sql(db,"INSERT INTO campus_verification(id,user_id,campus_id,requested_membership_type,applicant_name,status) VALUES (%s,%s,%s,'STUDENT','Smoke','APPROVED')",verification,USERS[0],CAMPUS)
  sql(db,"INSERT INTO campus_membership(id,user_id,campus_id,membership_type,status,approved_at,last_verification_id) VALUES (%s,%s,%s,'STUDENT','ACTIVE',NOW(6),%s)",str(uuid.uuid4()),USERS[0],CAMPUS,verification)
  root=GATEWAY+'/auth/wechat/community/posts';h=lambda i:{'X-Miniapp-Session':TOKENS[i]}
  body={'campusId':CAMPUS,'textContent':'Smoke community post'}
  check('no session',401,req(root,body))
  check('nonmember',403,req(root,body,h(1)))
  check('cross campus',403,req(root,dict(body,campusId=str(uuid.uuid4())),h(0)))
  check('direct unsigned',401,req(ANIMAL+'/internal/v1/miniapp/community/posts',body,{'X-User-Id':USERS[0]}))
  created=check('post submitted',200,req(root,body,h(0)));post=created['id'];assert created['reviewStatus']=='PENDING'
  sql(db,'USE animallink_animal');assert sql(db,'SELECT review_status FROM post WHERE id=%s',post)[0][0]=='PENDING'
  feed=check('public feed before approval',200,req(ANIMAL+'/api/v1/public/campuses/'+CAMPUS+'/feed'));assert all(x['id']!=post for x in feed['items'])
  sql(db,"UPDATE post SET review_status='APPROVED' WHERE id=%s",post)
  comment=check('comment submitted',200,req(root+'/'+post+'/comments',{'campusId':CAMPUS,'content':'Smoke comment'},h(0)));assert comment['reviewStatus']=='PENDING'
  assert sql(db,'SELECT review_status FROM comment WHERE id=%s',comment['id'])[0][0]=='PENDING'
  comments=check('public comments before approval',200,req(ANIMAL+'/api/v1/public/posts/'+post+'/comments'));assert all(x['id']!=comment['id'] for x in comments['items'])
  like=root+'/'+post+'/like?campusId='+CAMPUS
  assert check('initial like',200,req(like,headers=h(0)))=={'active':False,'count':0}
  assert check('like',200,req(like,headers=h(0),method='POST'))=={'active':True,'count':1}
  check('duplicate like',409,req(like,headers=h(0),method='POST'))
  assert check('current like',200,req(like,headers=h(0)))=={'active':True,'count':1}
  assert check('unlike',200,req(like,headers=h(0),method='DELETE'))=={'active':False,'count':0}
  assert check('repeat unlike',200,req(like,headers=h(0),method='DELETE'))=={'active':False,'count':0}
  check('nonmember like',403,req(like,headers=h(1),method='POST'))
  check('wrong campus like',403,req(root+'/'+post+'/like?campusId='+str(uuid.uuid4()),headers=h(0),method='POST'))
  assert sql(db,'SELECT COUNT(*) FROM post_like WHERE post_id=%s',post)[0][0]==0
  print('DB: post and comment pending; like count zero',flush=True)
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
