"""제안기 후보를 정답셋과 비교한다 (DAT-05). 객체 유형·속성·관계(연결 규칙). 값 단위 연결(id_links) 비교는 아직 없다.

    .\\.venv\\Scripts\\python.exe -m app.validators.evaluate_ontology <후보 JSON> <정답 폴더>

비교 기준은 원천 위치다(이름은 사람이 고칠 수 있어 채점하지 않는다).
- 객체 유형: OBJECT_MAPPING의 원천 표 ↔ 정답 source_tables. 종류(category) 일치율 따로.
- 속성: PROPERTY_MAPPING의 원천 컬럼 ↔ 정답 source_column. 자료형·식별자·개인정보 일치를 따로 센다.
보류(HELD) 후보도 함께 센다(보류는 빠진 것이 아니라 검토 대상이다).

--exclude-tables: AI 계정이 읽지 못하는 표(원천 권한 밖)를 정답과 후보 양쪽에서 빼고 센다. 정답 파일은 고치지 않는다.
"""

import argparse
import json
import sys
from pathlib import Path


def _prf(found: set, gold: set) -> dict:
    hit = found & gold
    return {
        "proposed": len(found),
        "gold": len(gold),
        "correct": len(hit),
        "precision": round(len(hit) / len(found), 4) if found else None,
        "recall": round(len(hit) / len(gold), 4) if gold else None,
        "false_positive": sorted(found - gold),
        "missed": sorted(gold - found),
    }


def _touches(text: str, tables: set[str]) -> bool:
    """원천.표 또는 원천.표.컬럼 문자열이 빼는 표에 속하는지."""
    return ".".join(text.split(".")[:2]) in tables


def _exclude(onto: dict, by_kind: dict, tables: set[str]) -> tuple[dict, dict]:
    """빼는 표에 걸친 정답·후보를 지운 사본. 관계는 한쪽 끝만 걸쳐도 뺀다."""
    if not tables:
        return onto, by_kind
    onto = {
        "object_types": [
            t
            for t in onto["object_types"]
            if not any(_touches(s, tables) for s in t["source_tables"])
        ],
        "properties": [
            p for p in onto["properties"] if not _touches(p["source_column"], tables)
        ],
        "link_types": [
            link
            for link in onto["link_types"]
            if not any(
                _touches(c, tables)
                for c in _side((link.get("via") or {}).get("from", ()))
                + _side((link.get("via") or {}).get("to", ()))
            )
        ],
    }
    dropped = {
        c["payload"]["object_type_code"]
        for c in by_kind.get("OBJECT_MAPPING", [])
        if _touches(c["payload"]["resource_key"], tables)
    }

    def keep(c: dict) -> bool:
        p = c["payload"]
        if p["kind"] == "OBJECT_MAPPING":
            return p["object_type_code"] not in dropped
        if p["kind"] == "OBJECT_TYPE":
            return p["code"] not in dropped
        if p["kind"] in ("PROPERTY", "PROPERTY_MAPPING"):
            return p["object_type_code"] not in dropped
        if p["kind"] == "LINK_RULE":
            return not any(
                x[side].split(".", 1)[0] in dropped
                for x in p["conditions"]
                for side in ("source_property", "target_property")
            )
        return True

    return onto, {k: [c for c in v if keep(c)] for k, v in by_kind.items()}


def evaluate(
    candidates: list[dict], gold_dir: Path, exclude_tables: set[str] = frozenset()
) -> dict:
    onto = json.loads((Path(gold_dir) / "ontology.json").read_text(encoding="utf-8"))
    by_kind: dict[str, list[dict]] = {}
    for c in candidates:
        by_kind.setdefault(c["payload"]["kind"], []).append(c)
    onto, by_kind = _exclude(onto, by_kind, set(exclude_tables))

    # 객체 유형: 원천 표 기준
    mapped = {
        c["payload"]["resource_key"]: c["payload"]["object_type_code"]
        for c in by_kind.get("OBJECT_MAPPING", [])
    }
    type_by_code = {c["payload"]["code"]: c for c in by_kind.get("OBJECT_TYPE", [])}
    gold_types = {t["source_tables"][0]: t for t in onto["object_types"]}
    objects = _prf(set(mapped), set(gold_types))
    category_rows = []
    for table in set(mapped) & set(gold_types):
        proposed = (
            type_by_code.get(mapped[table], {}).get("payload", {}).get("category")
        )
        category_rows.append((table, proposed, gold_types[table].get("category")))
    objects["category_accuracy"] = (
        round(sum(p == g for _, p, g in category_rows) / len(category_rows), 4)
        if category_rows
        else None
    )
    objects["category_wrong"] = sorted(
        f"{t}: 제안 {p} / 정답 {g}" for t, p, g in category_rows if p != g
    )

    # 속성: 원천 컬럼 기준
    def col(ref: dict) -> str:
        return f"{ref['source_id']}.{ref['table']}.{ref['column']}"

    prop_by_key = {
        (c["payload"]["object_type_code"], c["payload"]["code"]): c["payload"]
        for c in by_kind.get("PROPERTY", [])
    }
    proposed_cols = {}
    for c in by_kind.get("PROPERTY_MAPPING", []):
        p = c["payload"]
        payload = prop_by_key.get((p["object_type_code"], p["property_code"]))
        if payload:
            proposed_cols[col(p["source_column"])] = payload
    gold_props = {p["source_column"]: p for p in onto["properties"]}
    properties = _prf(set(proposed_cols), set(gold_props))
    shared = set(proposed_cols) & set(gold_props)

    def agree(field_p, field_g, transform=lambda v: v):
        rows = [
            (c, proposed_cols[c].get(field_p), transform(gold_props[c].get(field_g)))
            for c in sorted(shared)
            if gold_props[c].get(field_g) is not None
        ]
        wrong = [f"{c}: 제안 {p} / 정답 {g}" for c, p, g in rows if p != g]
        return {
            "compared": len(rows),
            "accuracy": round(1 - len(wrong) / len(rows), 4) if rows else None,
            "wrong": wrong,
        }

    properties["data_type"] = agree("data_type", "data_type")
    properties["is_identifier"] = agree("is_identifier", "is_identifier")
    properties["is_required"] = agree("is_required", "is_required")
    personal_found = {
        c for c in shared if proposed_cols[c].get("sensitivity") == "RESTRICTED"
    }
    personal_gold = {c for c in shared if gold_props[c].get("is_personal")}
    properties["is_personal"] = _prf(personal_found, personal_gold)
    links = evaluate_links(by_kind, onto)
    return {"object_types": objects, "properties": properties, "links": links}


def _side(value) -> tuple[str, ...]:
    return (value,) if isinstance(value, str) else tuple(value)


def evaluate_links(by_kind: dict, onto: dict) -> dict:
    """컬럼 관계 비교. 같은 컬럼 쌍이면 찾은 것으로 보고, 방향·조건·개수 관계는 따로 센다.

    대기(PENDING) 후보와 보류(HELD) 후보를 나눠 센다. 보류는 검토자에게 넘긴 것이라 정답이면 '보류 정답',
    정답이 아니면 '보류 오답'이다.
    """
    table_of = {
        c["payload"]["object_type_code"]: c["payload"]["resource_key"]
        for c in by_kind.get("OBJECT_MAPPING", [])
    }
    types = {c["payload"]["code"]: c["payload"] for c in by_kind.get("LINK_TYPE", [])}

    def col(prop: str) -> str:
        code, name = prop.split(".", 1)
        return f"{table_of.get(code, code)}.{name}"

    proposed = []
    for c in by_kind.get("LINK_RULE", []):
        p = c["payload"]
        src = tuple(col(x["source_property"]) for x in p["conditions"])
        dst = tuple(col(x["target_property"]) for x in p["conditions"])
        cond = None
        if p.get("source_filter"):
            cond = (col(p["source_filter"]["property"]), p["source_filter"]["equals"])
        card = types.get(p["link_type_code"], {}).get("cardinality")
        proposed.append(
            (
                src,
                dst,
                cond,
                card,
                c.get("review_status", "PENDING"),
                c.get("ai_confidence"),
            )
        )

    gold = []
    for link in onto["link_types"]:
        if link["derived_from"] != "column":
            continue
        via = link["via"]
        cond = None
        if via.get("condition"):
            cond = (via["condition"]["column"], via["condition"]["equals"])
        gold.append(
            (
                _side(via["from"]),
                _side(via["to"]),
                cond,
                link["cardinality"],
                link["code"],
            )
        )

    def same_pair(p, g):
        return {p[0], p[1]} == {g[0], g[1]} and (p[2] or None) == (g[2] or None)

    matched, direction_ok, card_ok = [], 0, 0
    used = set()
    for g in gold:
        hit = next(
            (i for i, p in enumerate(proposed) if i not in used and same_pair(p, g)),
            None,
        )
        if hit is None:
            continue
        used.add(hit)
        p = proposed[hit]
        matched.append((g, p))
        direction_ok += p[0] == g[0]
        card_ok += p[3] == g[3]
    fmt = lambda s, d, c: (
        f"{'+'.join(s)} → {'+'.join(d)}"
        + (f" [{c[0].rsplit('.', 1)[1]}={c[1]}]" if c else "")
    )
    pending = [p for i, p in enumerate(proposed) if p[4] == "PENDING"]
    held = [p for i, p in enumerate(proposed) if p[4] == "HELD"]
    pending_hit = [g for g, p in matched if p[4] == "PENDING"]
    held_hit = [g for g, p in matched if p[4] == "HELD"]
    wrong = [fmt(*p[:3]) for i, p in enumerate(proposed) if i not in used]
    return {
        "gold": len(gold),
        "proposed_pending": len(pending),
        "proposed_held": len(held),
        "found_pending": len(pending_hit),
        "found_held": len(held_hit),
        "recall_pending": round(len(pending_hit) / len(gold), 4) if gold else None,
        "recall_with_held": round(len(matched) / len(gold), 4) if gold else None,
        "precision_pending": round(len(pending_hit) / len(pending), 4)
        if pending
        else None,
        "direction_correct": f"{direction_ok}/{len(matched)}",
        "cardinality_correct": f"{card_ok}/{len(matched)}",
        "not_in_gold": wrong,
        "missed": [
            fmt(g[0], g[1], g[2]) for g in gold if g not in [m[0] for m in matched]
        ],
        "direction_wrong": [fmt(g[0], g[1], g[2]) for g, p in matched if p[0] != g[0]],
        "cardinality_wrong": [
            f"{fmt(g[0], g[1], g[2])}: 제안 {p[3]} / 정답 {g[3]}"
            for g, p in matched
            if p[3] != g[3]
        ],
        "held_correct": [fmt(g[0], g[1], g[2]) for g in held_hit],
        # 정답 관계 하나하나의 결과(후보 하나는 정답 하나에만 짝짓는다)
        "per_gold": [
            {
                "gold": g[4],
                "relation": fmt(g[0], g[1], g[2]),
                "status": next((p[4] for gg, p in matched if gg is g), "MISSED"),
                "direction_ok": next(
                    (p[0] == g[0] for gg, p in matched if gg is g), None
                ),
                "cardinality": next(
                    (f"제안 {p[3]} / 정답 {g[3]}" for gg, p in matched if gg is g), None
                ),
            }
            for g in gold
        ],
        "wrong_candidates": [
            {"relation": fmt(*p[:3]), "status": p[4], "ai_confidence": p[5]}
            for i, p in enumerate(proposed)
            if i not in used
        ],
        "totals": {
            "candidates": len(proposed),
            "correct": len(matched),
            "wrong": len(proposed) - len(matched),
            "precision_all": round(len(matched) / len(proposed), 4)
            if proposed
            else None,
        },
        "by_confidence_band": _bands(proposed, used),
    }


def _bands(proposed: list[tuple], correct_index: set[int]) -> dict:
    """신뢰도 구간별 실제 정답 비율. 점수가 높을수록 정답 비율도 높아야 한다."""
    out = {}
    for name, low, high in (
        ("높음 0.8~", 0.8, 1.01),
        ("보통 0.5~0.79", 0.5, 0.8),
        ("낮음 ~0.49", 0.0, 0.5),
    ):
        idx = [
            i for i, p in enumerate(proposed) if p[5] is not None and low <= p[5] < high
        ]
        hit = sum(1 for i in idx if i in correct_index)
        out[name] = f"{hit}/{len(idx)}" if idx else "0/0"
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="후보 ↔ 정답셋 비교")
    parser.add_argument("candidates", type=Path)
    parser.add_argument("gold", type=Path)
    parser.add_argument(
        "--exclude-tables",
        default="",
        help="채점에서 뺄 원천.표, 쉼표로 구분. 예: erp.sales_invoice,erp.gl_account",
    )
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    data = json.loads(args.candidates.read_text(encoding="utf-8"))
    excluded = {t.strip() for t in args.exclude_tables.split(",") if t.strip()}
    report = evaluate(data["candidates"], args.gold, excluded)
    if excluded:
        report["excluded_tables"] = sorted(excluded)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
