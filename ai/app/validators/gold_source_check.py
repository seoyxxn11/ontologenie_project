"""정답셋이 원천 데이터와 실제로 맞는지 확인한다 (DAT-04).

    .\\.venv\\Scripts\\python.exe -m app.validators.gold_source_check <원천 폴더> <정답 폴더>

- 오류(errors): 정답이 가리키는 표·컬럼이 없음, 키가 겹침, 값 단위 정답의 값이 원천에 없음 → 정답을 고쳐야 한다.
- 관찰(findings): 관계마다 실제로 이어지는 비율과 안 이어지는 값. 정답에 기록된 사례(일부러 넣은 오류 등)로
  설명되지 않는 미연결이 있으면 경고로 따로 센다.
분석기를 쓰지 않는다. 정답에 적힌 관계·규칙을 원천 값에 그대로 적용해 본다.
"""

import argparse
import csv
import json
import sys
from collections import Counter
from pathlib import Path

from app.rules.transforms import TransformError, TransformStep, apply_steps

TEXT_SOURCES = {"note"}  # 표가 아닌 글 원천


class Source:
    def __init__(self, root: Path):
        self.root = Path(root)
        self._rows: dict[str, list[dict]] = {}

    def has_table(self, table: str) -> bool:
        source, name = table.split(".", 1)
        return (self.root / source / f"{name}.csv").exists()

    def rows(self, table: str) -> list[dict]:
        if table not in self._rows:
            source, name = table.split(".", 1)
            with (self.root / source / f"{name}.csv").open(encoding="utf-8") as f:
                self._rows[table] = list(csv.DictReader(f))
        return self._rows[table]

    def has_column(self, ref: str) -> bool:
        table, column = ref.rsplit(".", 1)
        if not self.has_table(table):
            return False
        with (self.root / table.split(".")[0] / f"{table.split('.')[1]}.csv").open(
            encoding="utf-8"
        ) as f:
            return column in next(csv.reader(f))

    def schema(self, source: str) -> dict:
        path = self.root / source / "_schema.json"
        return json.loads(path.read_text(encoding="utf-8"))["tables"]


def _columns(side) -> list[str]:
    return [side] if isinstance(side, str) else list(side)


def _steps(raw) -> list[TransformStep]:
    return [TransformStep.model_validate(s) for s in raw or []]


def _convert(steps, value):
    try:
        return apply_steps(steps, value)
    except TransformError:
        return None


def check_relation(src: Source, link: dict, rules: dict) -> dict:
    via = link["via"]
    from_cols, to_cols = _columns(via["from"]), _columns(via["to"])
    result = {"code": link["code"], "from": from_cols, "to": to_cols, "errors": []}
    through = via.get("through")
    path_cols = [through["match"], through["take"]] if through else []
    missing = [c for c in from_cols + to_cols + path_cols if not src.has_column(c)]
    if missing:
        result["errors"].append(f"없는 컬럼: {missing}")
        return result
    rule = rules.get(link.get("mapping_rule") or "")
    transforms = (rule or {}).get("reference_transforms") or {}
    s_steps = _steps(transforms.get("source_transforms"))
    t_steps = _steps(transforms.get("target_transforms"))
    s_table, t_table = from_cols[0].rsplit(".", 1)[0], to_cols[0].rsplit(".", 1)[0]
    s_names = [c.rsplit(".", 1)[1] for c in from_cols]
    t_names = [c.rsplit(".", 1)[1] for c in to_cols]
    targets = Counter(
        tuple(_convert(t_steps, r[n]) for n in t_names) for r in src.rows(t_table)
    )
    if through:  # 출발 첫 컬럼 값으로 거쳐 가는 표를 찾아 그 표의 값으로 바꾼다
        p_table, p_key = through["match"].rsplit(".", 1)
        p_take = through["take"].rsplit(".", 1)[1]
        lookup = {r[p_key]: r[p_take] for r in src.rows(p_table)}
    condition = via.get("condition")
    total = matched = blank = 0
    unmatched = Counter()
    for r in src.rows(s_table):
        if (
            condition
            and r[condition["column"].rsplit(".", 1)[1]] != condition["equals"]
        ):
            continue
        raw = tuple(r[n] for n in s_names)
        if through:
            raw = (lookup.get(raw[0], ""), *raw[1:])
        if any(v == "" for v in raw):
            blank += 1
            continue
        total += 1
        key = tuple(_convert(s_steps, v) for v in raw)
        if targets.get(key):
            matched += 1
        else:
            unmatched["|".join(raw)] += 1
    duplicated = sum(1 for k, n in targets.items() if n > 1 and all(k))
    result.update(
        {
            "condition": condition,
            "rule": link.get("mapping_rule"),
            "rows": total,
            "blank": blank,
            "matched": matched,
            "coverage": round(matched / total, 4) if total else None,
            "unmatched_values": sorted(unmatched)[:10],
            "target_duplicate_keys": duplicated,
        }
    )
    if total == 0:
        result["errors"].append("조건에 맞는 값이 있는 행이 없음")
    if duplicated and via.get("key", "primary") == "primary":
        result["errors"].append(f"도착 키가 겹침 {duplicated}개")
    return result


def check(sample: Path, gold: Path) -> dict:
    src = Source(sample)
    load = lambda name: json.loads((gold / name).read_text(encoding="utf-8"))
    onto = load("ontology.json")
    rules = {r["rule_id"]: r for r in load("mapping_rules.json")}
    errors, report = [], {"object_types": 0, "properties": 0, "relations": []}

    for t in onto["object_types"]:
        for table in t["source_tables"]:
            if table in TEXT_SOURCES:
                continue
            if not src.has_table(table):
                errors.append(f"객체 {t['code']}: 표 없음 {table}")
                continue
            keys = [c.rsplit(".", 1)[1] for c in t["key_columns"]]
            if keys:
                seen = Counter(tuple(r[k] for k in keys) for r in src.rows(table))
                dup = sum(1 for n in seen.values() if n > 1)
                if dup:
                    errors.append(f"객체 {t['code']}: 키 {keys} 겹침 {dup}개")
        report["object_types"] += 1

    for p in onto["properties"]:
        if not src.has_column(p["source_column"]):
            errors.append(
                f"속성 {p['object_type']}.{p['code']}: 컬럼 없음 {p['source_column']}"
            )
        report["properties"] += 1

    for link in onto["link_types"]:
        if not link.get("via"):
            continue
        r = check_relation(src, link, rules)
        errors += [f"관계 {r['code']}: {e}" for e in r["errors"]]
        report["relations"].append(r)

    explained = {
        (i["column"], i["raw_value"]) for i in load("integrity_issues.json")
    } | {
        (rules[i["rule_id"]]["from"], i["from_value"])
        for i in load("id_links.json")
        if i["status"] != "matched" and i["rule_id"] in rules
    }
    known = {
        (link["code"], k["value"])
        for link in onto["link_types"]
        for k in link.get("known_unmatched", [])
    }
    unexplained = []
    for r in report["relations"]:
        if len(r["from"]) != 1:
            continue
        for value in r.get("unmatched_values", []):
            if (r["from"][0], value) not in explained and (
                r["code"],
                value,
            ) not in known:
                unexplained.append(f"{r['code']}: {value}")
    for link in load("id_links.json"):
        rule = rules.get(link["rule_id"])
        if not rule or rule["rule_type"] == "name_alias" or "*" in rule["from"]:
            continue
        values = {
            r[rule["from"].rsplit(".", 1)[1]]
            for r in src.rows(rule["from"].rsplit(".", 1)[0])
        }
        if link["from_value"] not in values:
            errors.append(
                f"값 정답 {link['rule_id']}: 원천에 없는 값 {link['from_value']}"
            )
        if link["to_value"]:
            targets = {
                r[rule["to"].rsplit(".", 1)[1]]
                for r in src.rows(rule["to"].rsplit(".", 1)[0])
            }
            if link["to_value"] not in targets:
                errors.append(
                    f"값 정답 {link['rule_id']}: 도착에 없는 값 {link['to_value']}"
                )
    report["errors"] = errors
    report["unexplained_unmatched"] = unexplained
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="정답셋 ↔ 원천 데이터 대조")
    parser.add_argument("sample", type=Path)
    parser.add_argument("gold", type=Path)
    parser.add_argument("--json", type=Path, help="전체 결과 저장")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    report = check(args.sample, args.gold)
    print(
        f"객체 유형 {report['object_types']} · 속성 {report['properties']} · 관계 {len(report['relations'])}"
    )
    for r in report["relations"]:
        cov = "—" if r.get("coverage") is None else f"{r['coverage']:.1%}"
        cond = (
            f" (조건 {r['condition']['column']}={r['condition']['equals']})"
            if r.get("condition")
            else ""
        )
        print(
            f"- {r['code']}: {'+'.join(r['from'])} → {'+'.join(r['to'])}{cond} | "
            f"이어짐 {r.get('matched', 0)}/{r.get('rows', 0)} ({cov}), 빈 값 {r.get('blank', 0)}"
            + (
                f", 안 이어지는 값 {r['unmatched_values']}"
                if r.get("unmatched_values")
                else ""
            )
        )
    print(
        f"\n설명 안 된 미연결 {len(report['unexplained_unmatched'])}건: {report['unexplained_unmatched']}"
    )
    print(f"오류 {len(report['errors'])}건")
    for e in report["errors"]:
        print(f"  ! {e}")
    if args.json:
        args.json.write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
