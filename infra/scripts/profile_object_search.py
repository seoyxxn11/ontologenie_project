"""72번: 같은 운영 데이터에서 기존/작업 트리 검색 SQL을 읽기 전용으로 비교한다.

운영 파일·스키마·인덱스는 바꾸지 않는다. API 왕복 시간이 아닌 SQL 실행 계획을
측정하며 결과에는 객체 값 대신 계획의 시간·건수와 전후 결과 동등 여부만 남긴다.
"""

import argparse
import json
from pathlib import Path
import re
import subprocess
import textwrap


REPOSITORY = "backend/src/main/java/com/axcore/platform/ontology/repository/"


def block(source: str, name: str) -> str:
    match = re.search(r"String " + re.escape(name) + r'\s*=\s*"""(.*?)"""', source, re.S)
    if not match:
        raise ValueError(f"Missing SQL text block: {name}")
    return textwrap.dedent(match[1]).lstrip("\n")


def queries(repository: str, display: str) -> dict[str, str]:
    parts = re.search(r'String DISPLAY_JOINS\s*=(.*?);', display, re.S)
    if not parts:
        raise ValueError("Missing DISPLAY_JOINS composition")
    chunks = re.findall(r'"""(.*?)"""', parts[1], re.S)
    if len(chunks) != 2:
        raise ValueError("Unexpected DISPLAY_JOINS composition")
    joins = (
        textwrap.dedent(chunks[0]).lstrip("\n")
        + block(display, "SOURCE_SCOPE_JOINS")
        + textwrap.dedent(chunks[1]).lstrip("\n")
    )
    match = block(repository, "SEARCH_MATCH")
    matches = (
        "from object_instance oi\n"
        + joins
        + block(repository, "FILTERS")
        + "and " + match.replace("%1$s", ":pattern")
    )
    order = (
        "order by case when " + match.replace("%1$s", ":exactPattern")
        + " then 0 else 1 end, oi.id limit 20 offset 0"
    )
    return {"page": block(repository, "SELECT") + matches + order,
            "count": "select count(*) " + matches}


REMOTE = r'''
import concurrent.futures,datetime,json,math,statistics,subprocess,sys,threading,time
payload=json.loads(sys.stdin.readline())
def query(sql):
    command='PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=1000" psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
    full="BEGIN READ ONLY; SET LOCAL search_path TO platform; SET LOCAL jit = off;\n"+sql+";\nROLLBACK;"
    p=subprocess.run(['docker','exec','-i','postgres','sh','-c',command],input=full,text=True,capture_output=True,timeout=15)
    if p.returncode: raise RuntimeError('Read-only SQL failed (exit %s)'%p.returncode)
    return json.loads(p.stdout.strip())
scope=query("SELECT json_build_object('visible',array_agg(t.id ORDER BY t.id),'masked',coalesce(array_agg(t.id ORDER BY t.id) FILTER(WHERE NOT s.can_view_restricted),ARRAY[]::bigint[])) FROM object_type t JOIN role_data_scope s ON s.access_group=t.access_group JOIN role r ON r.id=s.role_id WHERE r.code='ADMIN'")
def bind(sql):
    return sql.replace(':visibleTypeIds',','.join(map(str,scope['visible']))).replace(':maskedTypeIds','ARRAY['+','.join(map(str,scope['masked']))+']::bigint[]').replace(':exactPattern',"'P103'").replace(':pattern',"'%P103%'").replace(':status',"'ACTIVE'")
queries={version:{kind:bind(sql) for kind,sql in items.items()} for version,items in payload['queries'].items()}
def nodes(plan):
    result=[]
    def visit(node):
        result.append({key:node[key] for key in ['Node Type','Relation Name','Actual Total Time','Actual Rows','Actual Loops','Shared Hit Blocks','Shared Read Blocks'] if key in node})
        for child in node.get('Plans',[]): visit(child)
    visit(plan)
    return result
out={'captured_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'SQL execution only; read-only transaction; jit=off like current API','baseline_ref':payload['baseline_ref'],'scopes':scope,'plans':[],'comparisons':[]}
for version,items in queries.items():
    for kind,sql in items.items():
        plan=query('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '+sql)[0]
        out['plans'].append({'version':version,'kind':kind,'planning_ms':plan['Planning Time'],'execution_ms':plan['Execution Time'],'nodes':nodes(plan['Plan'])})
for kind in ['page','count']:
    # 페이지의 순서도 포함해서 비교한다. 업무 응답은 서버 밖으로 내보내지 않는다.
    before=queries['before'][kind]
    after=queries['after'][kind]
    equality=query("WITH before_rows AS MATERIALIZED ("+before+"), after_rows AS MATERIALIZED ("+after+") SELECT json_build_object('same_rows',(SELECT coalesce(jsonb_agg(to_jsonb(b)), '[]'::jsonb) FROM before_rows b) = (SELECT coalesce(jsonb_agg(to_jsonb(a)), '[]'::jsonb) FROM after_rows a))")
    out['comparisons'].append({'kind':kind,**equality})
if not all(row['same_rows'] for row in out['comparisons']):
    raise RuntimeError('Before/after query results differ')
out['benchmarks']=[]
if payload['samples']:
    for concurrency in [1,5]:
        for version in ['before','after']:
            samples=[]
            with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
                for wave in range(payload['samples']//concurrency):
                    gate=threading.Barrier(concurrency)
                    def one(index):
                        gate.wait(timeout=15)
                        started=datetime.datetime.now(datetime.timezone.utc).isoformat()
                        timings={}
                        for kind in ['page','count']:
                            plan=query('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '+queries[version][kind])[0]
                            timings[kind+'_execution_ms']=plan['Execution Time']
                        return {'index':index,'started_at':started,**timings,'total_sql_execution_ms':sum(timings.values())}
                    futures=[executor.submit(one,len(samples)+offset) for offset in range(concurrency)]
                    samples.extend(f.result() for f in futures)
            values=[s['total_sql_execution_ms'] for s in samples]
            out['benchmarks'].append({'version':version,'concurrency':concurrency,'samples':samples,'n':len(values),'mean_ms':statistics.mean(values),'p95_ms':sorted(values)[math.ceil(len(values)*.95)-1]})
out['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
print(json.dumps(out))
'''


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-ref", default="origin/develop")
    parser.add_argument("--host", default="axcore")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--samples", type=int, choices=[0, 20], default=0)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Choose a new output path to preserve prior evidence")

    def original(name: str) -> str:
        return subprocess.run(
            ["git", "show", f"{args.baseline_ref}:{REPOSITORY}{name}"],
            text=True, encoding="utf-8", capture_output=True, check=True,
        ).stdout

    before = queries(original("ObjectQueryRepository.java"), original("ObjectDisplaySql.java"))
    after = queries(
        Path(REPOSITORY, "ObjectQueryRepository.java").read_text(encoding="utf-8"),
        Path(REPOSITORY, "ObjectDisplaySql.java").read_text(encoding="utf-8"),
    )
    payload = {"baseline_ref": args.baseline_ref, "samples": args.samples,
               "queries": {"before": before, "after": after}}
    # SSH stdin은 프로그램에 사용하므로 측정 입력을 고정 JSON으로 함께 전달한다.
    program = REMOTE.replace(
        "payload=json.loads(sys.stdin.readline())",
        "payload=json.loads(" + repr(json.dumps(payload)) + ")",
    )
    result = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", "-o",
         "StrictHostKeyChecking=yes", args.host, "python3", "-"],
        input=program, text=True, encoding="utf-8", capture_output=True, check=True,
        timeout=360,
    )
    report = json.loads(result.stdout)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8", newline="\n",
    )
    print(json.dumps({"plans":[{k:v for k,v in p.items() if k!='nodes'} for p in report['plans']],
                      "comparisons":report['comparisons'],
                      "benchmarks":[{k:v for k,v in b.items() if k!='samples'} for b in report['benchmarks']]}))


if __name__ == "__main__":
    main()
