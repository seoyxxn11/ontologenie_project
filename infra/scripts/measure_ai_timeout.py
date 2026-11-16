"""55번: 비밀값 없이 SSH로 접수 지표와 비동기 작업 완료 시간을 읽는다.

서버 파일·설정·업무 데이터는 변경하지 않는다. 실제 요청은 로그인한 앱에서 실행한다.
snapshot 전후의 POST 지표 차이는 backend 처리 시간이며 nginx 왕복 시간이 아니다.
Agent는 완료 시각 컬럼이 없어 마지막 실행 중 관측과 최초 종료 관측을 기록한다.
"""

import argparse
import json
import math
from pathlib import Path
import re
import subprocess
import sys


ROUTES = {
    "/api/v1/analyses",
    "/api/v1/agent/conversations/{id}/messages",
}


def parse_metrics(text: str) -> list[dict]:
    values = {}
    for line in text.splitlines():
        match = re.fullmatch(
            r"http_server_requests_seconds_(count|sum)\{(.*)\}\s+(\S+)", line
        )
        if not match:
            continue
        labels = dict(re.findall(r'(\w+)="([^"\\]*)"', match[2]))
        if labels.get("method") != "POST" or labels.get("uri") not in ROUTES:
            continue
        key = (labels["uri"], labels.get("status", "unknown"))
        value = float(match[3])
        if not math.isfinite(value) or value < 0:
            raise ValueError("Invalid request metric")
        item = values.setdefault(key, {})
        item[match[1]] = item.get(match[1], 0) + value
    if any(set(item) != {"count", "sum"} for item in values.values()):
        raise ValueError("Incomplete request metric series")
    return [
        {"uri": uri, "status": status, **values[(uri, status)]}
        for uri, status in sorted(values)
    ]


def compare(before: dict, after: dict, route: str, expected_count: int) -> dict:
    if before["backend_started_at"] != after["backend_started_at"]:
        raise ValueError("Backend restarted between snapshots; repeat the measurement")

    def counter(snapshot):
        return next(
            (m for m in snapshot["requests"] if m["uri"] == route and m["status"] == "202"),
            {"count": 0, "sum": 0},
        )

    first, last = counter(before), counter(after)
    count = last["count"] - first["count"]
    total = last["sum"] - first["sum"]
    if expected_count <= 0 or count != expected_count or total < 0:
        raise ValueError("Unexpected request count or reset; do not attribute this sample")
    return {
        "uri": route,
        "status": "202",
        "count": count,
        "backend_mean_seconds": total / count,
        "scope": "backend HTTP handling only; excludes nginx/client and async completion",
    }


REMOTE = r'''
import datetime,json,subprocess,sys,time,urllib.request
mode,kind,job_id,after_id,conversation_id,interval,limit=sys.argv[1:]
job_id,after_id,conversation_id=int(job_id),int(after_id),int(conversation_id)
interval,limit=float(interval),float(limit)
def query(sql):
    command='PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=5000" psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
    result=subprocess.run(['docker','exec','-i','postgres','sh','-c',command],input='SET search_path TO platform;\n'+sql,text=True,capture_output=True,timeout=15)
    if result.returncode:
        raise RuntimeError('Read-only database query failed (exit %s)'%result.returncode)
    return json.loads(result.stdout.strip() or 'null')
def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()
if mode=='snapshot':
    started=subprocess.run(['docker','inspect','--format','{{.State.StartedAt}}','backend'],text=True,capture_output=True,check=True).stdout.strip()
    with urllib.request.urlopen('http://127.0.0.1:18080/actuator/prometheus',timeout=5) as response:
        raw=response.read().decode()
        metrics='\n'.join(line for line in raw.splitlines() if line.startswith(('http_server_requests_seconds_count{','http_server_requests_seconds_sum{')) and 'method="POST"' in line and ('uri="/api/v1/analyses"' in line or 'uri="/api/v1/agent/conversations/{id}/messages"' in line))
    data=query("SELECT json_build_object('sources',(SELECT json_object_agg(connection_status,n) FROM (SELECT connection_status,count(*) n FROM data_source GROUP BY connection_status)s),'source_resources',(SELECT count(*) FROM source_resource),'object_types',(SELECT count(*) FROM object_type),'objects',(SELECT count(*) FROM object_instance),'last_analysis_id',(SELECT coalesce(max(id),0) FROM analysis_run),'last_agent_message_id',(SELECT coalesce(max(id),0) FROM agent_message WHERE sender='AGENT'));")
    print(json.dumps({'captured_at':now(),'backend_started_at':started,'data':data,'metrics':metrics}),flush=True)
else:
    deadline=time.monotonic()+limit
    previous_status=None
    last_running_at=None
    while time.monotonic()<deadline:
        selector='id=%s'%job_id if job_id else 'id>%s'%after_id
        if kind=='analysis':
            sql="SELECT row_to_json(r) FROM (SELECT id,status,requested_at,started_at,finished_at,extract(epoch FROM(finished_at-requested_at)) AS job_total_seconds FROM analysis_run WHERE "+selector+" ORDER BY id LIMIT 1)r;"
        else:
            selector+=" AND sender='AGENT'"
            if conversation_id:
                selector+=' AND conversation_id=%s'%conversation_id
            sql="SELECT row_to_json(r) FROM (SELECT id,status,created_at FROM agent_message WHERE "+selector+" ORDER BY id LIMIT 1)r;"
        row=query(sql)
        observed_at=now()
        if row:
            job_id=row['id']
            if row['status']=='RUNNING':
                last_running_at=observed_at
            row.update(observed_at=observed_at,last_running_observed_at=last_running_at)
            if row['status'] in ('SUCCEEDED','FAILED'):
                row['terminal_observed_at']=observed_at
                if kind=='agent':
                    created=datetime.datetime.fromisoformat(row['created_at'])
                    end=datetime.datetime.fromisoformat(observed_at)
                    row['terminal_observed_seconds']=(end-created).total_seconds()
                    row['last_running_observed_seconds']=None if not last_running_at else (datetime.datetime.fromisoformat(last_running_at)-created).total_seconds()
                print(json.dumps(row),flush=True)
                sys.exit(0 if row['status']=='SUCCEEDED' else 2)
            if previous_status!=row['status']:
                print(json.dumps(row),flush=True)
                previous_status=row['status']
        time.sleep(interval)
    print(json.dumps({'measurement':'incomplete','reason':'observation deadline reached','job_id':job_id,'observed_at':now()}),flush=True)
    sys.exit(3)
'''


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["snapshot", "watch", "compare"])
    parser.add_argument("--host", default="axcore")
    parser.add_argument("--kind", choices=["analysis", "agent"], default="analysis")
    parser.add_argument("--id", type=int, default=0)
    parser.add_argument("--after-id", type=int, default=0)
    parser.add_argument("--conversation-id", type=int, default=0)
    parser.add_argument("--interval", type=float, default=2)
    parser.add_argument("--limit", type=float, default=900)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--before", type=Path)
    parser.add_argument("--after", type=Path)
    parser.add_argument("--expected-count", type=int, default=1)
    args = parser.parse_args()
    if (
        min(args.id, args.after_id, args.conversation_id) < 0
        or not math.isfinite(args.interval)
        or not math.isfinite(args.limit)
        or args.interval < 0.5
        or args.limit <= 0
        or args.expected_count <= 0
    ):
        parser.error("IDs must be nonnegative; interval >= 0.5; limit and count > 0")
    if args.mode == "compare":
        if not args.before or not args.after:
            parser.error("compare requires --before and --after")
        route = (
            "/api/v1/analyses" if args.kind == "analysis"
            else "/api/v1/agent/conversations/{id}/messages"
        )
        result = compare(
            json.loads(args.before.read_text(encoding="utf-8")),
            json.loads(args.after.read_text(encoding="utf-8")),
            route,
            args.expected_count,
        )
        output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
        exit_code = 0
    else:
        command = [
            "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8",
            "-o", "StrictHostKeyChecking=yes", args.host, "python3", "-",
            args.mode, args.kind, str(args.id), str(args.after_id),
            str(args.conversation_id), str(args.interval), str(args.limit),
        ]
        process = subprocess.Popen(
            command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            text=True, encoding="utf-8",
        )
        process.stdin.write(REMOTE)
        process.stdin.close()
        lines = []
        for line in process.stdout:
            result = json.loads(line)
            if args.mode == "snapshot":
                result["requests"] = parse_metrics(result.pop("metrics"))
            output_line = json.dumps(result, ensure_ascii=False)
            print(output_line, flush=True)
            lines.append(output_line)
        exit_code = process.wait()
        output = "\n".join(lines) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    if args.mode == "compare":
        print(output, end="")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
