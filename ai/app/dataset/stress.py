"""스트레스 평가 데이터 C: 개발용 데이터 A(v1.0)를 변형해 분석기가 처음 보는 표기·잡음·미끼를 견디는지 본다.

    .\\.venv\\Scripts\\python.exe -m app.dataset.stress --base v1.0

분석기를 고정한 뒤 만들고, 결과를 보고 분석기를 고치지 않는다(docs/evaluation.md 0-1절).
출력: data/data-<base>-stress (AI 입력), data/gold-<base>-stress/expected.json (기대 결과)
"""

import argparse
import csv
import json
import random
import shutil
import sys
from pathlib import Path

DATA_ROOT = Path(__file__).resolve().parents[2] / "data"
SEED = 20261026

CHANGES = [
    {
        "id": "C1-WORKER-FORMAT",
        "what": "MES 작업자 번호를 31 → W-031 형식으로 바꿈 (작업자·실적·불량·인수인계 메모 표 모두)",
        "expect": "작업자 → 직원 연결을 다른 변환(접두어 떼기·0 채우기·GP 붙이기)으로 찾는다",
    },
    {
        "id": "C2-SO-REF-FORMAT",
        "what": "작업지시 수주 참조 2610021 → SO2610021 (이미 ERP 표기인 값·빈 값은 그대로)",
        "expect": "작업지시 → 수주 연결을 찾는다",
    },
    {
        "id": "C3-CASE-SPACE",
        "what": "실적 설비 코드 5%를 소문자로, 3%에 뒤 공백",
        "expect": "실적 → 설비 연결을 찾는다 (다듬기·대문자 변환)",
    },
    {
        "id": "C4-TYPO",
        "what": "자재 투입 품목 코드 3%에 오타 (CL-440 → CL-44O)",
        "expect": "연결은 찾고, 오타 값은 안 이어지는 값으로 남는다",
    },
    {
        "id": "C5-DECOY-SEQ",
        "what": "비가동 표에 하루 안 순번 컬럼 shift_seq(0001~) 추가. 고정자산 번호 FA-0001과 숫자만 우연히 같다",
        "expect": "어느 표와도 잇지 않는다",
    },
    {
        "id": "C6-DECOY-BADGE",
        "what": "근태 표에 출입증 번호 badge_no(E + 세 자리) 추가. 어떤 키와도 관계없다",
        "expect": "어느 표와도 잇지 않는다",
    },
]
DECOYS = ["mes.downtime.shift_seq", "erp.attendance.badge_no"]


def _read(path: Path) -> tuple[list[str], list[dict]]:
    with path.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        return list(reader.fieldnames), list(reader)


def _write(path: Path, header: list[str], rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=header, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def _add_column(folder: Path, table: str, name: str, type_: str, comment: str):
    path = folder / "_schema.json"
    schema = json.loads(path.read_text(encoding="utf-8"))
    schema["tables"][table]["columns"][name] = {"type": type_, "comment": comment}
    path.write_text(
        json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def build(base: str = "v1.0", root: Path = DATA_ROOT, force: bool = False) -> dict:
    src = root / f"data-{base}"
    out = root / f"data-{base}-stress"
    gold_out = root / f"gold-{base}-stress"
    for folder in (out, gold_out):
        if folder.exists():
            if not force:
                raise FileExistsError(f"이미 있습니다. 다시 만들려면 --force: {folder}")
            shutil.rmtree(folder)
    for source in ("erp", "mes"):
        shutil.copytree(src / source, out / source)
    rng = random.Random(SEED)
    mes, erp = out / "mes", out / "erp"

    def worker(value: str) -> str:
        return f"W-{int(value):03d}" if value else value

    for table, column in [
        ("worker", "id"),
        ("production_result", "worker_id"),
        ("defect", "detected_by"),
        ("handover_note", "author_id"),
    ]:
        header, rows = _read(mes / f"{table}.csv")
        for r in rows:
            r[column] = worker(r[column])
        _write(mes / f"{table}.csv", header, rows)

    header, rows = _read(mes / "work_order.csv")
    for r in rows:
        if r["so_ref"].isdigit():
            r["so_ref"] = "SO" + r["so_ref"]
    _write(mes / "work_order.csv", header, rows)

    header, rows = _read(mes / "production_result.csv")
    for r in rows:
        x = rng.random()
        if x < 0.05:
            r["equipment_code"] = r["equipment_code"].lower()
        elif x < 0.08:
            r["equipment_code"] += " "
    _write(mes / "production_result.csv", header, rows)

    header, rows = _read(mes / "material_issue.csv")
    for r in rows:
        if rng.random() < 0.03:
            r["item_code"] = r["item_code"][:-1] + "O"
    _write(mes / "material_issue.csv", header, rows)

    header, rows = _read(mes / "downtime.csv")
    per_day: dict[str, int] = {}
    for r in rows:
        day = r["started_at"][:10]
        per_day[day] = per_day.get(day, 0) + 1
        r["shift_seq"] = f"{per_day[day] + rng.randint(0, 6):04d}"
    _write(mes / "downtime.csv", [*header, "shift_seq"], rows)
    _add_column(mes, "downtime", "shift_seq", "varchar(10)", "교대 안 순번")

    header, rows = _read(erp / "attendance.csv")
    badges = {}
    for r in rows:
        badges.setdefault(r["emp_no"], f"E{rng.randint(100, 999)}")
        r["badge_no"] = badges[r["emp_no"]]
    _write(erp / "attendance.csv", [*header, "badge_no"], rows)
    _add_column(erp, "attendance", "badge_no", "varchar(10)", "출입증 번호")

    ontology = json.loads(
        (root / f"gold-{base}" / "ontology.json").read_text(encoding="utf-8")
    )
    relations = [
        {"from": l["via"]["from"], "to": l["via"]["to"], "level": l["level"]}
        for l in ontology["link_types"]
        if l.get("via")
    ]
    gold_out.mkdir(parents=True)
    expected = {
        "base": base,
        "seed": SEED,
        "changes": CHANGES,
        "relations": relations,
        "decoy_columns": DECOYS,
    }
    (gold_out / "expected.json").write_text(
        json.dumps(expected, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return {"sample": str(out), "gold": str(gold_out), "changes": len(CHANGES)}


def evaluate(sample: Path, expected: dict) -> dict:
    """분석기 결과를 기대 결과와 비교한다. 필수 관계 재현율, 정답에 없는 관계, 미끼 연결."""
    from app.analyzers.structured import analyze_sources

    def key(side) -> str:
        return side if isinstance(side, str) else "+".join(side)

    links = analyze_sources(sample).links
    found = {(l.from_ref, l.to_ref) for l in links}
    found |= {(t, f) for f, t in found}
    known = {(key(r["from"]), key(r["to"])) for r in expected["relations"]}
    known |= {(t, f) for f, t in known}
    must = [r for r in expected["relations"] if r["level"] == "must"]
    missed = [
        f"{key(r['from'])} → {key(r['to'])}"
        for r in must
        if (key(r["from"]), key(r["to"])) not in found
    ]
    extra = [
        f"{l.from_ref} → {l.to_ref}"
        for l in links
        if (l.from_ref, l.to_ref) not in known
    ]
    decoy = [
        f"{l.from_ref} → {l.to_ref}"
        for l in links
        if l.from_ref in expected["decoy_columns"]
        or l.to_ref in expected["decoy_columns"]
    ]
    return {
        "must_found": len(must) - len(missed),
        "must_total": len(must),
        "missed": missed,
        "not_in_gold": extra,
        "decoy_links": decoy,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="스트레스 평가 데이터 C 생성·평가")
    parser.add_argument("--base", default="v1.0")
    parser.add_argument("--out", type=Path, default=DATA_ROOT)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--evaluate", action="store_true", help="만든 뒤 분석기로 평가")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    result = build(args.base, args.out, args.force)
    if args.evaluate:
        expected = json.loads(
            (Path(result["gold"]) / "expected.json").read_text(encoding="utf-8")
        )
        result["evaluation"] = evaluate(Path(result["sample"]), expected)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
