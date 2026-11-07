#!/usr/bin/env python3
"""Real Java processes and HTTP; Identity is an external test server."""
import base64
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
