#!/usr/bin/env python3
"""Real Java processes and HTTP; Identity is an external test server."""
import base64
import hashlib
import hmac
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import boto3
import pymysql

USER = "00000000-0000-0000-0000-000000000401"
CAMPUS = "10000000-0000-0000-0000-000000000401"
TASK, RESULT, RECORD = (str(uuid.uuid4()) for _ in range(3))
IDENTITY = "http://127.0.0.1:18081"
ANIMAL = "http://127.0.0.1:18082"
INTELLIGENCE = "http://127.0.0.1:18085"


class IdentityStub(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/api/v1/users/me":
            result = {"id": USER, "displayName": "Smoke user", "accountStatus": "ACTIVE", "systemRole": "USER"}
        elif self.path == f"/internal/v1/users/{USER}/campus-memberships/{CAMPUS}":
            result = {"exists": True, "membershipType": "STUDENT", "status": "ACTIVE"}
        else:
            self.send_error(404)
            return
        data = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def request(url, payload=None, headers=None):
    data = None if payload is None else json.dumps(payload).encode()
    headers = {"Content-Type": "application/json", **(headers or {})}
    req = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()


def wait_ready(url, process):
    end = time.monotonic() + 120
    while time.monotonic() < end:
        if process.poll() is not None:
            raise RuntimeError(f"process exited {process.returncode}: {url}")
        try:
            code, body = request(url + "/actuator/health/readiness")
            if code == 200 and json.loads(body)["status"] == "UP":
                print(f"READY {url}", flush=True)
                return
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(1)
    raise RuntimeError(f"readiness timeout: {url}")


def sql(conn, statement, *params):
    with conn.cursor() as cursor:
        cursor.execute(statement, params)
        return cursor.fetchall()


def signed_late_finalize(key, task, record, attempt, version, animal, draft):
    path = "/internal/v1/observation-finalizations"
    payload = {"userId": USER, "campusId": CAMPUS, "sourceAiTaskId": task,
               "sourceMatchingRecordId": record, "attemptId": attempt, "fencingVersion": version,
               "decisionType": "SELECT_EXISTING", "selectedAnimalId": animal,
               "postText": "Smoke test observation", "mediaObjectKeys": ["ai-input/smoke.jpg"],
               "confirmedDraft": draft}
    body = json.dumps(payload).encode()
    def b64(value):
        return base64.urlsafe_b64encode(value).rstrip(b"=")
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT", "kid": "observation-v1"}
    claims = {"iss": "intelligence-service", "sub": "intelligence-service", "aud": ["animal-service"],
              "scope": ["observation:finalize"], "method": "POST", "path": path,
              "actor": USER, "task": task, "campus": CAMPUS, "record": record, "attempt": attempt,
              "version": version, "decision": "SELECT_EXISTING", "animal": animal,
              "body_sha256": hashlib.sha256(body).hexdigest(), "iat": now, "exp": now + 20,
              "jti": str(uuid.uuid4())}
    signing = b64(json.dumps(header, separators=(",", ":")).encode()) + b"." + b64(json.dumps(claims, separators=(",", ":")).encode())
    token = (signing + b"." + b64(hmac.new(key, signing, hashlib.sha256).digest())).decode()
    return request(ANIMAL + path, payload, {"X-Internal-Service": "intelligence-service", "Authorization": "Bearer " + token})


def seed_candidate(conn, animal):
    task, result, record = (str(uuid.uuid4()) for _ in range(3))
    draft = {"species": "CAT", "sex": "UNKNOWN", "coatColor": "orange", "distinctiveFeatures": [],
             "estimatedCount": 1, "possibleAbnormality": False, "abnormalFlags": [],
             "locationDescription": "campus", "confidence": 0.8, "fieldConfidence": {},
             "warnings": [], "unknownFields": []}
    data = json.dumps(draft)
    sql(conn, "USE animallink_intelligence")
    sql(conn, """INSERT INTO ai_task (id,user_id,campus_id,task_type,status,model_provider,model_name,
        prompt_version,input_summary,created_at,confirmed_at,confirmed_by,version)
        VALUES (%s,%s,%s,'ANIMAL_OBSERVATION_PARSE','SUCCEEDED','test','test','v1','{}',NOW(6),NOW(6),%s,2)""", task, USER, CAMPUS, USER)
    sql(conn, "INSERT INTO ai_task_media (task_id,sort_order,object_key) VALUES (%s,0,'ai-input/smoke.jpg')", task)
    sql(conn, """INSERT INTO ai_result (id,task_id,result_type,raw_response,structured_json,schema_version,created_at)
        VALUES (%s,%s,'ANIMAL_OBSERVATION_DRAFT',%s,%s,'1.0',NOW(6))""", result, task, data, data)
    sql(conn, """INSERT INTO ai_confirmation (id,task_id,result_id,confirmed_structured_json,was_modified,confirmed_by,confirmed_at)
        VALUES (%s,%s,%s,%s,false,%s,NOW(6))""", str(uuid.uuid4()), task, result, data, USER)
    sql(conn, """INSERT INTO matching_record (id,user_id,ai_task_id,campus_id,algorithm_version,weight_version,
        experiment_code,weights_json,top_k,candidate_count,low_confidence,created_at)
        VALUES (%s,%s,%s,%s,'smoke','smoke','D','{}',3,1,false,NOW(6))""", record, USER, task, CAMPUS)
    sql(conn, """INSERT INTO matching_candidate (id,matching_record_id,rank_number,animal_id,display_name,
        species,final_score,reasons_json,missing_dimensions_json,created_at)
        VALUES (%s,%s,1,%s,'smoke candidate','CAT',0.9,JSON_ARRAY(),JSON_ARRAY(),NOW(6))""",
        str(uuid.uuid4()), record, animal)
    return task, record, draft


def main():
    processes = []
    logs = []
    stub = ThreadingHTTPServer(("127.0.0.1", 18081), IdentityStub)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    connection = pymysql.connect(host="127.0.0.1", user="root", password="smoke-password", autocommit=True)
    try:
        sql(connection, "CREATE DATABASE animallink_animal")
        sql(connection, "CREATE DATABASE animallink_intelligence")
        storage = boto3.client("s3", endpoint_url="http://127.0.0.1:9000",
                               aws_access_key_id="smoke-access", aws_secret_access_key="smoke-secret-123",
                               region_name="us-east-1")
        for attempt in range(60):
            try:
                storage.list_buckets()
                break
            except Exception:
                if attempt == 59:
                    raise
                time.sleep(1)
        for bucket in ("animallink-animal", "animallink-intelligence"):
            storage.create_bucket(Bucket=bucket)
        storage.put_object(Bucket="animallink-intelligence", Key="ai-input/smoke.jpg",
                           Body=b"smoke image", ContentType="image/jpeg")
        env = os.environ.copy()
        env.update({"ANIMALLINK_AUTHENTICATION_OBSERVATION_ASSERTION_KEY": base64.b64encode(os.urandom(32)).decode(),
                    "ANIMAL_DB_USER": "root", "ANIMAL_DB_PASSWORD": "smoke-password",
                    "INTELLIGENCE_DB_USER": "root", "INTELLIGENCE_DB_PASSWORD": "smoke-password",
                    "IDENTITY_SERVICE_BASE_URL": IDENTITY, "IDENTITY_SERVICE_URL": IDENTITY,
                    "ANIMAL_SERVICE_URL": ANIMAL, "MINIO_ENDPOINT": "http://127.0.0.1:9000",
                    "MINIO_ACCESS_KEY": "smoke-access", "MINIO_SECRET_KEY": "smoke-secret-123",
                    "ANIMALLINK_MESSAGING_ENABLED": "false"})
        common = ["--spring.cloud.nacos.discovery.enabled=false", "--spring.cloud.nacos.config.enabled=false",
                  "--spring.cloud.discovery.enabled=false", "--spring.flyway.locations=classpath:db/migration",
                  "--management.endpoint.health.probes.enabled=true"]
        for name, port in (("animal", 18082), ("intelligence", 18085)):
            log = open(f"{name}-smoke.log", "w+", encoding="utf-8")
            logs.append(log)
            jar = f"backend/{name}-service/target/{name}-service-0.1.0-SNAPSHOT.jar"
            command = ["java", "-jar", jar, f"--server.port={port}", *common]
            print("START", " ".join(command), flush=True)
            processes.append(subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT))
        wait_ready(ANIMAL, processes[0])
        wait_ready(INTELLIGENCE, processes[1])

        sql(connection, "USE animallink_intelligence")
        draft = json.dumps({"species": "CAT", "sex": "UNKNOWN", "coatColor": "orange", "distinctiveFeatures": [],
                            "estimatedCount": 1, "possibleAbnormality": False, "abnormalFlags": [],
                            "locationDescription": "campus", "confidence": 0.8, "fieldConfidence": {},
                            "warnings": [], "unknownFields": []})
        sql(connection, """INSERT INTO ai_task (id,user_id,campus_id,task_type,status,model_provider,model_name,
            prompt_version,input_summary,created_at,confirmed_at,confirmed_by,version)
            VALUES (%s,%s,%s,'ANIMAL_OBSERVATION_PARSE','SUCCEEDED','test','test','v1','{}',NOW(6),NOW(6),%s,2)""", TASK, USER, CAMPUS, USER)
        sql(connection, "INSERT INTO ai_task_media (task_id,sort_order,object_key) VALUES (%s,0,'ai-input/smoke.jpg')", TASK)
        sql(connection, """INSERT INTO ai_result (id,task_id,result_type,raw_response,structured_json,schema_version,created_at)
            VALUES (%s,%s,'ANIMAL_OBSERVATION_DRAFT',%s,%s,'1.0',NOW(6))""", RESULT, TASK, draft, draft)
        sql(connection, """INSERT INTO ai_confirmation (id,task_id,result_id,confirmed_structured_json,was_modified,confirmed_by,confirmed_at)
            VALUES (%s,%s,%s,%s,false,%s,NOW(6))""", str(uuid.uuid4()), TASK, RESULT, draft, USER)
        sql(connection, """INSERT INTO matching_record (id,user_id,ai_task_id,campus_id,algorithm_version,weight_version,
            experiment_code,weights_json,top_k,candidate_count,low_confidence,created_at)
            VALUES (%s,%s,%s,%s,'smoke','smoke','D','{}',3,0,true,NOW(6))""", RECORD, USER, TASK, CAMPUS)

        url = INTELLIGENCE + f"/api/v1/ai/matches/{RECORD}/finalize"
        payload = {"decisionType": "NO_MATCH", "postText": "Smoke test observation"}
        status, body = request(url, payload, {"X-User-Id": USER})
        print("Intelligence finalize HTTP", status, body.decode(), flush=True)
        assert status == 200, body
        first = json.loads(body)
        sql(connection, "USE animallink_animal")
        rows = sql(connection, """SELECT p.id, p.animal_id, f.post_id, g.status
            FROM post p JOIN observation_finalization f ON f.post_id=p.id
            JOIN observation_attempt_gate g ON g.source_ai_task_id=f.source_ai_task_id
            WHERE f.source_ai_task_id=%s""", TASK)
        assert len(rows) == 1 and rows[0][0] == rows[0][2] and rows[0][1] is None and rows[0][3] == "FINALIZED", rows
        assert rows[0][0] == first["createdPost"]["id"]
        print("DB assertion: one unbound post, matching finalization, FINALIZED gate", flush=True)

        invalid = {"userId": USER, "campusId": CAMPUS, "sourceAiTaskId": str(uuid.uuid4()),
                   "sourceMatchingRecordId": str(uuid.uuid4()), "attemptId": str(uuid.uuid4()), "decisionType": "NO_MATCH"}
        status, body = request(ANIMAL + "/internal/v1/observation-finalizations/attempts/register", invalid,
                               {"X-Internal-Service": "intelligence-service"})
        print("Unsigned register HTTP", status, flush=True)
        assert status == 403, body
        assert sql(connection, "SELECT COUNT(*) FROM observation_attempt_gate")[0][0] == 1
        assert sql(connection, "SELECT COUNT(*) FROM post")[0][0] == 1
        status, body = request(url, payload, {"X-User-Id": USER})
        print("Repeated Intelligence finalize HTTP", status, flush=True)
        assert status == 200 and json.loads(body)["createdPost"]["id"] == rows[0][0]
        assert sql(connection, "SELECT COUNT(*) FROM post")[0][0] == 1
        assert sql(connection, "SELECT COUNT(*) FROM observation_finalization")[0][0] == 1
        print("SMOKE PASS: distinct processes, signed HTTP, persisted facts, unsigned rejection, retry", flush=True)

        active = str(uuid.uuid4())
        sql(connection, "INSERT INTO animal (id,campus_id,display_name,species) VALUES (%s,%s,'active candidate','CAT')", active, CAMPUS)
        active_task, active_record, _ = seed_candidate(connection, active)
        active_url = INTELLIGENCE + f"/api/v1/ai/matches/{active_record}/finalize"
        selected = {"decisionType": "SELECT_EXISTING", "selectedAnimalId": active, "postText": "Smoke test observation"}
        status, body = request(active_url, selected, {"X-User-Id": USER})
        print("SELECT_EXISTING HTTP", status, flush=True)
        assert status == 200, body
        selected_post = json.loads(body)["createdPost"]["id"]
        sql(connection, "USE animallink_animal")
        selected_rows = sql(connection, """SELECT p.id,p.animal_id,f.selected_animal_id,g.status
            FROM post p JOIN observation_finalization f ON f.post_id=p.id
            JOIN observation_attempt_gate g ON g.source_ai_task_id=f.source_ai_task_id
            WHERE f.source_ai_task_id=%s""", active_task)
        assert selected_rows == ((selected_post, active, active, "FINALIZED"),), selected_rows
        status, body = request(active_url, selected, {"X-User-Id": USER})
        print("SELECT_EXISTING retry HTTP", status, flush=True)
        assert status == 200 and json.loads(body)["createdPost"]["id"] == selected_post
        assert sql(connection, "SELECT COUNT(*) FROM observation_finalization WHERE source_ai_task_id=%s", active_task)[0][0] == 1
        assert sql(connection, "SELECT COUNT(*) FROM post WHERE source_ai_task_id=%s", active_task)[0][0] == 1
        print("DB assertion: active Animal associated; one post and finalization after retry", flush=True)

        expired = str(uuid.uuid4())
        sql(connection, "INSERT INTO animal (id,campus_id,display_name,species) VALUES (%s,%s,'stale candidate','CAT')", expired, CAMPUS)
        stale_task, stale_record, stale_draft = seed_candidate(connection, expired)
        sql(connection, "USE animallink_animal")
        sql(connection, "UPDATE animal SET identity_status='ARCHIVED' WHERE id=%s", expired)
        stale_url = INTELLIGENCE + f"/api/v1/ai/matches/{stale_record}/finalize"
        stale_selection = {"decisionType": "SELECT_EXISTING", "selectedAnimalId": expired, "postText": "Smoke test observation"}
        status, body = request(stale_url, stale_selection, {"X-User-Id": USER})
        print("Expired SELECT_EXISTING HTTP", status, body.decode(), flush=True)
        assert status == 409 and json.loads(body)["code"] == "SELECTED_ANIMAL_ARCHIVED", body
        old = sql(connection, "SELECT attempt_id,fencing_version,status FROM observation_attempt_gate WHERE source_ai_task_id=%s", stale_task)
        assert len(old) == 1 and old[0][1:] == (1, "RETIRED"), old
        old_attempt, old_version, _ = old[0]
        assert sql(connection, "SELECT COUNT(*) FROM post WHERE source_ai_task_id=%s", stale_task)[0][0] == 0
        sql(connection, "USE animallink_intelligence")
        assert sql(connection, "SELECT COUNT(*) FROM matching_decision_intent_history WHERE source_ai_task_id=%s AND deterministic_failure_code='SELECTED_ANIMAL_ARCHIVED'", stale_task)[0][0] == 1
        key = base64.b64decode(env["ANIMALLINK_AUTHENTICATION_OBSERVATION_ASSERTION_KEY"])
        status, body = signed_late_finalize(key, stale_task, stale_record, old_attempt, old_version, expired, stale_draft)
        print("Signed late old-attempt finalize HTTP", status, body.decode(), flush=True)
        assert status == 409 and json.loads(body)["code"] == "OBSERVATION_ATTEMPT_CONFLICT", body
        status, body = request(stale_url, payload, {"X-User-Id": USER})
        print("Safe NO_MATCH redecision HTTP", status, body.decode(), flush=True)
        assert status == 200 and json.loads(body)["decision"]["decisionType"] == "NO_MATCH", body
        new_post = json.loads(body)["createdPost"]["id"]
        sql(connection, "USE animallink_animal")
        assert sql(connection, "SELECT fencing_version,status FROM observation_attempt_gate WHERE source_ai_task_id=%s", stale_task) == ((2, "FINALIZED"),)
        assert sql(connection, "SELECT fencing_version,status FROM observation_attempt_history WHERE source_ai_task_id=%s ORDER BY fencing_version", stale_task) == ((1, "RETIRED"), (2, "FINALIZED"))
        assert sql(connection, "SELECT id,animal_id FROM post WHERE source_ai_task_id=%s", stale_task) == ((new_post, None),)
        assert sql(connection, "SELECT COUNT(*) FROM observation_finalization WHERE source_ai_task_id=%s", stale_task)[0][0] == 1
        print("DB assertion: old attempt RETIRED, signed late write rejected, new version FINALIZED with one unbound post", flush=True)
    except Exception:
        for log in logs:
            log.flush()
            log.seek(0)
            print(f"--- {log.name} ---\n{log.read()[-30000:]}", file=sys.stderr)
        raise
    finally:
        stub.shutdown()
        for process in processes:
            process.terminate()
        for process in processes:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for log in logs:
            log.close()
        connection.close()


if __name__ == "__main__":
    main()
