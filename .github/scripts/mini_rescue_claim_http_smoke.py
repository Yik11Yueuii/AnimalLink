#!/usr/bin/env python3
"""Three real JVMs, isolated MySQL, seeded test sessions. No WeChat login is performed."""
import base64, hashlib, hmac, json, os, subprocess, time, urllib.error, urllib.request, uuid
import pymysql

GATEWAY, IDENTITY, INCIDENT = 'http://127.0.0.1:18080', 'http://127.0.0.1:18081', 'http://127.0.0.1:18083'
CAMPUS = '22000000-0000-4000-8000-000000000001'
OTHER_CAMPUS = '22000000-0000-4000-8000-000000000002'
USERS = [str(uuid.uuid4()) for _ in range(3)]
TOKENS = [base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip('=') for _ in USERS]
EVENT, DRAFT = str(uuid.uuid4()), str(uuid.uuid4())
KEY = os.urandom(32)
KEYS = 'test=' + base64.b64encode(KEY).decode()
processes, logs = [], []


def request(url, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json', **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as r: return r.status, r.read()
    except urllib.error.HTTPError as e: return e.code, e.read()


def ready(url, process):
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        if process.poll() is not None: raise AssertionError('process exited: ' + url)
        try:
            code, body = request(url + '/actuator/health/readiness')
            if code == 200 and json.loads(body)['status'] == 'UP':
                print('READY', url, 'PID', process.pid, flush=True); return
        except (OSError, ValueError, KeyError): pass
        time.sleep(1)
    raise AssertionError('readiness timeout: ' + url)


def sql(db, query, *args):
    with db.cursor() as cur:
        cur.execute(query, args)
        return cur.fetchall()


def signed(path, actor, key=KEY):
    b64 = lambda x: base64.urlsafe_b64encode(x).rstrip(b'=')
    now = int(time.time())
    header = {'alg': 'HS256', 'typ': 'JWT', 'kid': 'test'}
    claims = {'iss': 'api-gateway', 'sub': 'api-gateway', 'aud': ['incident-service'], 'target': 'incident-service',
              'actor_id': actor, 'system_role': 'USER', 'scope': ['incident.case.claim'], 'request_method': 'POST',
              'request_path': path, 'iat': now, 'exp': now+20, 'jti': str(uuid.uuid4())}
    value = b64(json.dumps(header, separators=(',', ':')).encode()) + b'.' + b64(json.dumps(claims, separators=(',', ':')).encode())
    return (value + b'.' + b64(hmac.new(key, value, hashlib.sha256).digest())).decode()


def expect(label, expected, result):
    code, body = result
    print(label, 'HTTP', code, flush=True)
    if code != expected: raise AssertionError(label + ': expected ' + str(expected) + ' got ' + str(code) + ' ' + body.decode(errors='replace')[:600])
    return json.loads(body) if body else {}


def main():
    db = pymysql.connect(host='127.0.0.1', user='root', password='smoke-password', autocommit=True)
    try:
        for name in ('animallink_identity', 'animallink_incident'): sql(db, 'CREATE DATABASE ' + name + ' CHARACTER SET utf8mb4')
        env = os.environ.copy()
        env.update({'IDENTITY_DB_USER': 'root', 'IDENTITY_DB_PASSWORD': 'smoke-password', 'INCIDENT_DB_USER': 'root', 'INCIDENT_DB_PASSWORD': 'smoke-password',
                    'AUTH_SERVICE_ASSERTION_CURRENT_KEY_ID': 'test', 'AUTH_SERVICE_ASSERTION_KEYS': KEYS,
                    'IDENTITY_INTERNAL_BASE_URL': IDENTITY, 'IDENTITY_SERVICE_URL': IDENTITY,
                    'ANIMALLINK_MESSAGING_ENABLED': 'false', 'CREDENTIAL_PRECHECK_DISPATCHER_ENABLED': 'false',
                    'MINIO_ACCESS_KEY': 'smoke-access', 'MINIO_SECRET_KEY': 'smoke-secret-123'})
        common = ['--spring.cloud.nacos.discovery.enabled=false', '--spring.cloud.nacos.config.enabled=false',
                  '--spring.cloud.discovery.enabled=false', '--management.health.rabbit.enabled=false',
                  '--spring.flyway.locations=classpath:db/migration', '--management.endpoint.health.probes.enabled=true',
                  '--animallink.authentication.incident-miniapp-assertion.keys=' + KEYS]
        for name, port in [('identity-service', 18081), ('incident-service', 18083), ('api-gateway', 18080)]:
            log = open(name + '-claim-smoke.log', 'w+', encoding='utf8'); logs.append(log)
            args = ['java', '-jar', 'backend/' + name + '/target/' + name + '-0.1.0-SNAPSHOT.jar', '--server.port=' + str(port), *common,
                    '--animallink.authentication.incident-miniapp-assertion.current-key-id=test',
                    '--animallink.authentication.incident-miniapp.base-url=' + INCIDENT]
            print('START', name, 'port', port, flush=True)
            processes.append(subprocess.Popen(args, env=env, stdout=log, stderr=subprocess.STDOUT))
        for url, proc in zip((IDENTITY, INCIDENT, GATEWAY), processes): ready(url, proc)
        sql(db, 'USE animallink_identity')
        for i, user in enumerate(USERS):
            sql(db, "INSERT INTO `user` (id,display_name,account_status,system_role) VALUES (%s,%s,'ACTIVE','USER')", user, 'smoke')
            sql(db, "INSERT INTO wechat_user_session (id,user_id,token_hash,created_at,last_seen_at,idle_expires_at,absolute_expires_at) VALUES (%s,%s,%s,NOW(6),NOW(6),DATE_ADD(NOW(6),INTERVAL 1 DAY),DATE_ADD(NOW(6),INTERVAL 2 DAY))", str(uuid.uuid4()), user, hashlib.sha256(TOKENS[i].encode()).digest())
            if i < 2: sql(db, "INSERT INTO volunteer_membership (id,user_id,campus_id,status) VALUES (%s,%s,%s,'ACTIVE')", str(uuid.uuid4()), user, CAMPUS)
        sql(db, 'USE animallink_incident')
        sql(db, "INSERT INTO event_draft (id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES (%s,%s,%s,'smoke',NOW(6),'public',NOW(6),NOW(6))", DRAFT, USERS[0], CAMPUS)
        sql(db, "INSERT INTO event (id,source_draft_id,reporter_user_id,campus_id,description,occurred_at,reported_at,public_location_description,exact_location_description,status,created_at,updated_at) VALUES (%s,%s,%s,%s,'smoke',NOW(6),NOW(6),'public','private location','VERIFIED',NOW(6),NOW(6))", EVENT,DRAFT,USERS[0],CAMPUS)
        path = '/auth/wechat/rescues/' + EVENT + '/claim'
        body = {'campusId': CAMPUS}
        claim = lambda token, value=body: request(GATEWAY + path, value, {'X-Miniapp-Session': token})
        expect('no session', 401, request(GATEWAY+path,body))
        expect('no volunteer', 403, claim(TOKENS[2]))
        expect('wrong campus', 403, claim(TOKENS[0], {'campusId': OTHER_CAMPUS}))
        expect('bad request', 400, claim(TOKENS[0], {'campusId': ''}))
        assert sql(db, 'SELECT COUNT(*) FROM animal_case')[0][0] == 0
        result = expect('active volunteer', 200, claim(TOKENS[0])); case_id = result['id']
        assert set(result) == {'id','eventId','campusId','status','claimStatus','claimedAt'}
        assert result['eventId'] == EVENT and result['campusId'] == CAMPUS
        assert expect('same volunteer repeat', 200, claim(TOKENS[0]))['id'] == case_id
        expect('other volunteer', 409, claim(TOKENS[1]))
        internal = '/api/v1/internal/miniapp/rescues/' + EVENT + '/claim'
        bad = signed(internal, USERS[0], os.urandom(32))
        expect('bad signature', 401, request(INCIDENT+internal,body,{'Authorization':'Bearer '+bad}))
        replay = signed(internal, USERS[0])
        expect('signed request', 200, request(INCIDENT+internal,body,{'Authorization':'Bearer '+replay}))
        expect('replay', 401, request(INCIDENT+internal,body,{'Authorization':'Bearer '+replay}))
        assert sql(db, 'SELECT COUNT(*) FROM animal_case WHERE event_id=%s', EVENT)[0][0] == 1
        processes[1].terminate(); processes[1].wait(timeout=20)
        expect('incident unavailable', 503, claim(TOKENS[0]))
        processes[0].terminate(); processes[0].wait(timeout=20)
        expect('identity unavailable', 503, claim(TOKENS[0]))
        print('DB: exactly one Case; claim response has no owner, location or internal fields', flush=True)
        print('NOTE: direct MySQL-seeded sessions; real WeChat login was not tested', flush=True)
    finally:
        for p in processes:
            if p.poll() is None: p.terminate()
        for p in processes:
            try: p.wait(timeout=15)
            except subprocess.TimeoutExpired: p.kill(); p.wait()
        for log in logs:
            log.flush(); log.seek(0)
            print('\n---', log.name, 'last lines ---')
            print(''.join(log.readlines()[-70:]))
            log.close()
        db.close()

if __name__ == '__main__': main()
