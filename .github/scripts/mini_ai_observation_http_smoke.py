#!/usr/bin/env python3
"""Independent Gateway, Identity and Intelligence JVMs with MySQL/MinIO; seeded sessions, mock provider."""
import base64, hashlib, hmac, json, os, secrets, subprocess, time, urllib.error, urllib.request, uuid
import boto3, pymysql
GATEWAY='http://127.0.0.1:18080'; IDENTITY='http://127.0.0.1:18081'; AI='http://127.0.0.1:18085'
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
  for name in ('animallink_identity','animallink_intelligence'):sql(db,'CREATE DATABASE '+name+' CHARACTER SET utf8mb4')
  subprocess.run(['docker','run','-d','--name','mini-ai-minio','-p','9000:9000','-e','MINIO_ROOT_USER='+access,'-e','MINIO_ROOT_PASSWORD='+secret,'animallink-ai-smoke','server','/data'],check=True,stdout=subprocess.DEVNULL)
  storage=boto3.client('s3',endpoint_url='http://127.0.0.1:9000',aws_access_key_id=access,aws_secret_access_key=secret,region_name='us-east-1')
  for i in range(60):
   try:storage.create_bucket(Bucket='animallink-intelligence');break
   except Exception:
    if i==59:raise
    time.sleep(1)
  env=os.environ.copy();env.update({'IDENTITY_DB_USER':'root','IDENTITY_DB_PASSWORD':'','INTELLIGENCE_DB_USER':'root','INTELLIGENCE_DB_PASSWORD':'','AUTH_SERVICE_ASSERTION_CURRENT_KEY_ID':'test','AUTH_SERVICE_ASSERTION_KEYS':keys,'IDENTITY_INTERNAL_BASE_URL':'http://identity-service','IDENTITY_SERVICE_URL':IDENTITY,'MINIO_ACCESS_KEY':access,'MINIO_SECRET_KEY':secret,'CREDENTIAL_PRECHECK_DISPATCHER_ENABLED':'false','AI_PROVIDER':'mock'})
  common=['--spring.cloud.nacos.discovery.enabled=false','--spring.cloud.nacos.config.enabled=false','--spring.cloud.discovery.client.simple.instances.identity-service[0].uri='+IDENTITY,'--spring.cloud.discovery.client.simple.instances.intelligence-service[0].uri='+AI,'--management.endpoint.health.probes.enabled=true','--animallink.authentication.intelligence-miniapp-assertion.keys='+keys,'--animallink.authentication.intelligence-miniapp-assertion.current-key-id=test']
  for name,port in [('identity-service',18081),('intelligence-service',18085),('api-gateway',18080)]:
   log=open(name+'-ai-smoke.log','w+',encoding='utf8');logs.append(log);args=['java','-jar','backend/'+name+'/target/'+name+'-0.1.0-SNAPSHOT.jar','--server.port='+str(port),*common];print('START',name,port,flush=True);processes.append(subprocess.Popen(args,env=env,stdout=log,stderr=subprocess.STDOUT))
  for url,p in zip((IDENTITY,AI,GATEWAY),processes):ready(url,p)
  sql(db,'USE animallink_identity')
  for i,user in enumerate(USERS):
   sql(db,"INSERT INTO `user` (id,display_name,account_status,system_role) VALUES (%s,'Smoke','ACTIVE','USER')",user)
   sql(db,"INSERT INTO wechat_user_session(id,user_id,token_hash,created_at,last_seen_at,idle_expires_at,absolute_expires_at) VALUES (%s,%s,%s,NOW(6),NOW(6),DATE_ADD(NOW(6),INTERVAL 1 DAY),DATE_ADD(NOW(6),INTERVAL 2 DAY))",str(uuid.uuid4()),user,hashlib.sha256(TOKENS[i].encode()).digest())
  verification=str(uuid.uuid4());sql(db,"INSERT INTO campus_verification(id,user_id,campus_id,requested_membership_type,applicant_name,status) VALUES (%s,%s,%s,'STUDENT','Smoke','APPROVED')",verification,USERS[0],CAMPUS)
  sql(db,"INSERT INTO campus_membership(id,user_id,campus_id,membership_type,status,approved_at,last_verification_id) VALUES (%s,%s,%s,'STUDENT','ACTIVE',NOW(6),%s)",str(uuid.uuid4()),USERS[0],CAMPUS,verification)
  root=GATEWAY+'/auth/wechat/observations/';h=lambda i:{'X-Miniapp-Session':TOKENS[i]}
  check('no session',401,req(root+'upload-url',{'campusId':CAMPUS}))
  check('session validation',200,req(GATEWAY+'/auth/wechat/me',headers=h(1)))
  path='/internal/v1/miniapp/observations/upload-url'; now=int(time.time()); encode=lambda b:base64.urlsafe_b64encode(b).rstrip(b'=')
  header={'alg':'HS256','typ':'JWT','kid':'test'}; claims={'iss':'api-gateway','sub':'api-gateway','aud':['intelligence-service'],'target':'intelligence-service','actor_id':USERS[1],'system_role':'USER','scope':['intelligence.observation.write'],'request_method':'POST','request_path':path,'iat':now,'exp':now+15,'jti':str(uuid.uuid4())}
  signing=encode(json.dumps(header,separators=(',',':')).encode())+b'.'+encode(json.dumps(claims,separators=(',',':')).encode()); assertion=(signing+b'.'+encode(hmac.new(assertion_key,signing,hashlib.sha256).digest())).decode()
  check('direct signed nonmember',403,req(AI+path,{'campusId':CAMPUS},{'Authorization':'Bearer '+assertion}))
  check('nonmember',403,req(root+'upload-url',{'campusId':CAMPUS},h(1)))
  upload=check('upload intent',200,req(root+'upload-url',{'campusId':CAMPUS},h(0)))
  import struct, zlib
  def chunk(kind, payload): return struct.pack('!I',len(payload))+kind+payload+struct.pack('!I',zlib.crc32(kind+payload)&0xffffffff)
  data=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!IIBBBBB',1,1,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\x80\x80\x80'))+chunk(b'IEND',b'')
  put=urllib.request.Request(upload['uploadUrl'],data=data,headers={'Content-Type':'image/png'},method='PUT')
  with urllib.request.urlopen(put,timeout=15) as response:assert response.status==200
  assert storage.get_object(Bucket='animallink-intelligence',Key=upload['objectKey'])['Body'].read()==data
  print('actual MinIO PUT 200, byte assertion passed',flush=True)
  body={'campusId':CAMPUS,'mediaObjectKeys':[upload['objectKey']],'text':'orange cat','locationDescription':'campus'}
  check('foreign media rejected',403,req(root+'parse',dict(body,mediaObjectKeys=['ai-input/'+USERS[1]+'/object']),h(0)))
  result=check('mock parse',200,req(root+'parse',body,h(0)));assert result['status']=='SUCCEEDED' and result['originalDraft'] and not result['confirmedAt']
  task=result['taskId']; check('other user task denied',404,req(root+task,headers=h(1)))
  draft=result['originalDraft'];draft['coatColor']='white';confirmed=check('human confirm',200,req(root+task+'/confirm',{'draft':draft},h(0)));assert confirmed['confirmedDraft']['coatColor']=='white' and confirmed['confirmedAt']
  check('duplicate confirm',409,req(root+task+'/confirm',{'draft':draft},h(0)))
  manual=check('manual draft',200,req(root+'manual',dict(body,draft=draft),h(0)));assert manual['modelProvider']=='MANUAL' and manual['confirmedAt'] is None
  check('manual confirm',200,req(root+manual['taskId']+'/confirm',{'draft':draft},h(0)))
  sql(db,'USE animallink_intelligence');assert sql(db,'SELECT COUNT(*) FROM ai_confirmation')[0][0]==2
  print('DB confirms two human decisions; no Animal identity or Post created',flush=True)
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
