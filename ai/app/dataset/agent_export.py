"""가상 프레스 데이터를 Agent 시나리오 시험(S15P31C106-341·342)용 객체·연결 파일로 내보낸다.

    python -m app.dataset.agent_export [--out <파일>]

정답 온톨로지(gold-v1.0)를 승인된 온톨로지로 보고 원천 CSV(data-v1.0) 한 행을 객체 하나로 만든다. 연결은 정답 관계를
정답 규칙대로 원천 값에 적용해 이어지는 짝만 만든다. 백엔드 시험 DB(Testcontainers)에만 넣는 파일이다(데이터 담당 확인 11-11).

- 회계 표 4개(매출·매입 전표, 회계 계정, 분개)는 11-04 원천 권한에서 빠져 서비스에 없으므로 뺀다.
- 원천 정책의 금지 표(근태·고정자산)와 금지 컬럼(직원 이름·직급 등, 작업자 이름)은 정답 온톨로지에 없어 따라서 빠진다(S15P31C106-406).
- 메모 글에서 뽑는 관계(derived_from=text)는 이번에 넣지 않는다.
- 규칙 연결(R-SO 등)은 정답 값 목록(id_links.json)의 matched와 같아야 한다. 이어지지 않는 값은 expectedUnlinked로 따로 남긴다.
- 속성 값은 백엔드 원천 변환(SourceRowConverter)과 같은 표기로 바꾼다.
"""

import argparse
import gzip
import hashlib
import json
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal
from pathlib import Path

from app.rules.transforms import TransformError, TransformStep, apply_steps
from app.validators.gold_source_check import Source

DATA = Path(__file__).resolve().parents[2] / "data"
SOURCE_DIR = DATA / "data-v1.0"
GOLD_DIR = DATA / "gold-v1.0"
DEFAULT_OUT = (
    Path(__file__).resolve().parents[3]
    / "backend/src/test/resources/agent-scenario/press-v1.0.json.gz"
)

EXCLUDED_TABLES = {
    "erp.sales_invoice",
    "erp.purchase_invoice",
    "erp.gl_account",
    "erp.journal_entry",
}

# 역할별 조회 범위(V13)의 접근 그룹. 사용자 결정 11-11.
ACCESS_GROUPS = {
    "PRODUCTION": [
        "Equipment",
        "Worker",
        "Routing",
        "WorkOrder",
        "WorkOrderOperation",
        "ProductionResult",
        "MaterialIssue",
        "Downtime",
        "SensorReading",
        "Defect",
        "Inspection",
        "Die",
        "MaintenanceOrder",
        "HandoverNote",
    ],
    "SALES_SUPPLY": [
        "Item",
        "Vendor",
        "PurchaseOrder",
        "PurchaseOrderLine",
        "GoodsReceipt",
        "Stock",
        "SalesOrder",
        "SalesOrderLine",
        "Shipment",
    ],
    # FINANCE 그룹의 유일한 유형이던 고정자산(FixedAsset)과 HR의 근태(Attendance)는 금지 표라 없다(406)
    "HR": ["Employee", "Department"],
}

SOURCES = {
    "erp": {"code": "PRESS_ERP", "name": "00프레스 ERP"},
    "mes": {"code": "PRESS_MES", "name": "00프레스 MES"},
}

# 원천 timestamp에는 시간대가 없다. 백엔드 axcore.source-load.zone-id 기본값(Asia/Seoul)과 같다.
# 서울은 일광 절약 시간이 없어 고정 +09:00으로 둔다(tzdata가 없는 CI 이미지에서도 돈다).
SOURCE_ZONE = timezone(timedelta(hours=9))
RULE_REVISION = 1
DISPLAY_NAME_MAX_LENGTH = 300  # object_instance.display_name varchar(300)


def _load(gold_dir: Path, name: str):
    return json.loads((gold_dir / name).read_text(encoding="utf-8"))


def _table(column_ref: str) -> str:
    return column_ref.rsplit(".", 1)[0]


def _column(column_ref: str) -> str:
    return column_ref.rsplit(".", 1)[1]


def _columns(side) -> list[str]:
    return [side] if isinstance(side, str) else list(side)


def _steps(raw) -> list[TransformStep]:
    return [TransformStep.model_validate(s) for s in raw or []]


def _convert(steps: list[TransformStep], value: str):
    try:
        return apply_steps(steps, value)
    except TransformError:
        return None


def _java_time(value: time) -> str:
    """Java LocalTime.toString과 같게 초가 0이면 뺀다."""
    return value.strftime("%H:%M") if value.second == 0 else value.strftime("%H:%M:%S")


def typed_value(raw: str, data_type: str):
    """원천 문자열을 백엔드가 객체 속성에 저장하는 표기로 바꾼다. 빈 칸은 None이다."""
    if raw == "":
        return None
    if data_type == "STRING":
        return raw
    if data_type == "INTEGER":
        return int(raw)
    if data_type == "DECIMAL":
        # BigDecimal.toPlainString처럼 원천의 소수 자릿수를 그대로 둔다.
        return format(Decimal(raw), "f")
    if data_type == "DATE":
        return date.fromisoformat(raw).isoformat()
    if data_type == "TIME":
        return _java_time(time.fromisoformat(raw))
    if data_type == "DATETIME":
        moment = datetime.fromisoformat(raw).replace(tzinfo=SOURCE_ZONE)
        return f"{moment.date().isoformat()}T{_java_time(moment.time())}+09:00"
    raise ValueError(f"지원하지 않는 자료형: {data_type}")


def key_text(raw: str, data_type: str) -> str:
    """원천 키 값을 백엔드 SourceKeyText와 같은 문자열로 바꾼다. timestamp는 시간대 없는 ISO 날짜시각이다."""
    if data_type == "INTEGER":
        return str(int(raw))
    if data_type == "DECIMAL":
        return format(Decimal(raw), "f")
    if data_type == "DATE":
        return date.fromisoformat(raw).isoformat()
    if data_type == "TIME":
        return time.fromisoformat(raw).isoformat()
    if data_type == "DATETIME":
        return datetime.fromisoformat(raw).isoformat(sep="T")
    return raw


def _source_key(row: dict, keys: list[str], column_types: dict[str, str]) -> dict:
    return {k: key_text(row[k], column_types[k]) for k in keys}


def _column_types(props: list[dict]) -> dict[str, str]:
    return {_column(p["source_column"]): p["data_type"] for p in props}


def display_name(values: dict, display: str | None, source_key: dict) -> str:
    """백엔드 SourceRowConverter와 같다. 대표 속성 값, 없으면 원천 키 값을 " / "로 이은 것. 300자에서 자른다."""
    label = (
        "" if display is None or values.get(display) is None else str(values[display])
    )
    if not label.strip():
        label = " / ".join(str(v) for v in source_key.values())
    return label[:DISPLAY_NAME_MAX_LENGTH]


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _access_group(type_code: str) -> str:
    for group, codes in ACCESS_GROUPS.items():
        if type_code in codes:
            return group
    raise ValueError(f"접근 그룹이 없는 유형: {type_code}")


def build(source_dir: Path = SOURCE_DIR, gold_dir: Path = GOLD_DIR) -> dict:
    src = Source(source_dir)
    onto = _load(gold_dir, "ontology.json")
    gold_rules = {r["rule_id"]: r for r in _load(gold_dir, "mapping_rules.json")}

    types = [
        t for t in onto["object_types"] if t["source_tables"][0] not in EXCLUDED_TABLES
    ]
    type_codes = {t["code"] for t in types}
    type_by_code = {t["code"]: t for t in types}
    props_by_type: dict[str, list[dict]] = {}
    for p in onto["properties"]:
        if p["object_type"] in type_codes:
            props_by_type.setdefault(p["object_type"], []).append(p)

    object_types, properties = [], []
    for t in types:
        props = props_by_type[t["code"]]
        display = next(
            (
                p["code"]
                for p in props
                if (p["code"] == "name" or p["code"].endswith("_name"))
                and not p["is_personal"]
            ),
            None,
        )
        object_types.append(
            {
                "code": t["code"],
                "name": t["name"],
                "accessGroup": _access_group(t["code"]),
                "sourceResource": t["source_tables"][0],
                "keyColumns": [_column(c) for c in t["key_columns"]],
                "displayProperty": display,
            }
        )
        for p in props:
            properties.append(
                {
                    "objectType": t["code"],
                    "code": p["code"],
                    "name": p["name"],
                    "dataType": p["data_type"],
                    "isIdentifier": p["is_identifier"],
                    "isRequired": p["is_required"],
                    "sensitivity": "RESTRICTED" if p["is_personal"] else "NORMAL",
                    "sourceColumn": _column(p["source_column"]),
                }
            )

    objects = []
    for t in types:
        table = t["source_tables"][0]
        props = props_by_type[t["code"]]
        keys = [_column(c) for c in t["key_columns"]]
        column_types = _column_types(props)
        display = next(
            o["displayProperty"] for o in object_types if o["code"] == t["code"]
        )
        for row in src.rows(table):
            values = {
                p["code"]: typed_value(row[_column(p["source_column"])], p["data_type"])
                for p in props
            }
            source_key = _source_key(row, keys, column_types)
            objects.append(
                {
                    "objectType": t["code"],
                    "sourceKey": source_key,
                    "displayName": display_name(values, display, source_key),
                    "properties": values,
                }
            )

    link_types, rules, links, unlinked = [], [], [], []
    for link in onto["link_types"]:
        if link["derived_from"] != "column":
            continue
        if (
            link["source_type"] not in type_codes
            or link["target_type"] not in type_codes
        ):
            continue
        built = _build_link(src, link, type_by_code, props_by_type, gold_rules)
        link_types.append(built["linkType"])
        if built["rule"]:
            rules.append(built["rule"])
        links.extend(built["links"])
        unlinked.extend(built["unlinked"])

    reasons = _unlinked_reasons(gold_dir)
    known = {
        (link["code"], k["value"]): k["reason"]
        for link in onto["link_types"]
        for k in link.get("known_unmatched") or []
    }
    for item in unlinked:
        item["reason"] = reasons.get((item["goldRule"], item["value"])) or known.get(
            (item["linkType"], item["value"])
        )

    resources = sorted({t["source_tables"][0] for t in types})
    inputs = sorted(
        [*(source_dir / "erp").glob("*.csv"), *(source_dir / "mes").glob("*.csv")]
    ) + [gold_dir / n for n in ("ontology.json", "mapping_rules.json", "id_links.json")]
    return {
        "manifest": {
            "dataset": "data-v1.0 + gold-v1.0",
            "generator": "ai/app/dataset/agent_export.py",
            "excludedTables": sorted(EXCLUDED_TABLES),
            "excludedLinks": "메모 글에서 뽑는 관계(derived_from=text)",
            "counts": {
                "objectTypes": len(object_types),
                "properties": len(properties),
                "objects": len(objects),
                "linkTypes": len(link_types),
                "rules": len(rules),
                "links": len(links),
                "expectedUnlinked": len(unlinked),
            },
            "inputs": {
                p.relative_to(source_dir.parent).as_posix(): _sha256(p) for p in inputs
            },
        },
        "dataSources": [
            {**SOURCES[s], "resources": [r for r in resources if r.startswith(f"{s}.")]}
            for s in ("erp", "mes")
        ],
        "objectTypes": object_types,
        "properties": properties,
        "linkTypes": link_types,
        "rules": rules,
        "objects": objects,
        "links": links,
        "expectedUnlinked": unlinked,
    }


def _build_link(src, link, type_by_code, props_by_type, gold_rules) -> dict:
    via = link["via"]
    from_cols, to_cols = _columns(via["from"]), _columns(via["to"])
    s_type, t_type = (
        type_by_code[link["source_type"]],
        type_by_code[link["target_type"]],
    )
    s_props = {
        _column(p["source_column"]): p["code"] for p in props_by_type[s_type["code"]]
    }
    t_props = {
        _column(p["source_column"]): p["code"] for p in props_by_type[t_type["code"]]
    }
    gold_rule = link.get("mapping_rule")
    transforms = (gold_rules.get(gold_rule) or {}).get("reference_transforms") or {}
    s_raw_steps = transforms.get("source_transforms") or []
    t_raw_steps = transforms.get("target_transforms") or []
    s_steps, t_steps = _steps(s_raw_steps), _steps(t_raw_steps)
    through = via.get("through")
    condition = via.get("condition")
    # 거쳐 가는 표가 있는 관계는 규칙(비교 조건)으로 나타낼 수 없어 확정 연결(MANUAL)로 넣는다.
    origin = "MANUAL" if through else "RULE"
    rule_code = link["code"].upper()

    s_names = [_column(c) for c in from_cols]
    t_names = [_column(c) for c in to_cols]
    s_keys = [_column(c) for c in s_type["key_columns"]]
    t_keys = [_column(c) for c in t_type["key_columns"]]
    s_column_types = _column_types(props_by_type[s_type["code"]])
    t_column_types = _column_types(props_by_type[t_type["code"]])
    targets: dict[tuple, list[dict]] = {}
    for r in src.rows(_table(to_cols[0])):
        key = tuple(_convert(t_steps, r[n]) for n in t_names)
        targets.setdefault(key, []).append(r)
    lookup = None
    if through:
        lookup = {
            r[_column(through["match"])]: r[_column(through["take"])]
            for r in src.rows(_table(through["match"]))
        }

    links, unlinked = [], []
    for r in src.rows(_table(from_cols[0])):
        if condition and r[_column(condition["column"])] != condition["equals"]:
            continue
        raw = [r[n] for n in s_names]
        if through:
            raw = [lookup.get(raw[0], ""), *raw[1:]]
        source_key = _source_key(r, s_keys, s_column_types)
        if any(v == "" for v in raw):
            continue
        key = tuple(_convert(s_steps, v) for v in raw)
        matched = targets.get(key) if all(v is not None for v in key) else None
        if not matched:
            unlinked.append(
                {
                    "linkType": link["code"],
                    "goldRule": gold_rule,
                    "source": {"objectType": s_type["code"], "sourceKey": source_key},
                    "value": "|".join(raw),
                }
            )
            continue
        for target in matched:
            seq = [str(i + 1) for i in range(len(raw))]
            evidence = {
                "matchedValues": dict(zip(seq, key)),
                "rawValues": dict(zip(seq, raw)),
                "targetRawValues": dict(zip(seq, (target[n] for n in t_names))),
            }
            if origin == "RULE":
                evidence = {
                    "ruleCode": rule_code,
                    "revision": RULE_REVISION,
                    **evidence,
                }
            links.append(
                {
                    "linkType": link["code"],
                    "origin": origin,
                    "source": {"objectType": s_type["code"], "sourceKey": source_key},
                    "target": {
                        "objectType": t_type["code"],
                        "sourceKey": _source_key(target, t_keys, t_column_types),
                    },
                    "evidence": evidence,
                }
            )

    rule = None
    if origin == "RULE":
        rule = {
            "code": rule_code,
            "revision": RULE_REVISION,
            "linkType": link["code"],
            "goldRule": gold_rule,
            "sourceFilter": (
                {
                    "property": f"{s_type['code']}.{s_props[_column(condition['column'])]}",
                    "equals": condition["equals"],
                }
                if condition
                else {}
            ),
            "targetFilter": {},
            "conditions": [
                {
                    "sequenceNo": i + 1,
                    "sourceProperty": s_props[s_name],
                    "targetProperty": t_props[t_name],
                    "operator": "EQ",
                    "sourceTransforms": s_raw_steps,
                    "targetTransforms": t_raw_steps,
                }
                for i, (s_name, t_name) in enumerate(zip(s_names, t_names))
            ],
        }
    return {
        "linkType": {
            "code": link["code"],
            "name": f"{s_type['name']} → {t_type['name']}",
            "description": link.get("note") or link["name"],
            "sourceType": s_type["code"],
            "targetType": t_type["code"],
            "cardinality": link["cardinality"],
            # 연결을 넣는 방식(object_link.origin). link_type.origin(만든 주체)과 다르다.
            "linkOrigin": origin,
            "goldRule": gold_rule,
        },
        "rule": rule,
        "links": links,
        "unlinked": unlinked,
    }


def _unlinked_reasons(gold_dir: Path) -> dict[tuple, str]:
    return {
        (i["rule_id"], i["from_value"]): i["reason"]
        for i in _load(gold_dir, "id_links.json")
        if i["status"] != "matched"
    }


def encode(data: dict) -> bytes:
    """압축 JSON(gzip). 압축 시각을 0으로 고정해 같은 입력이면 같은 파일이 나온다."""
    raw = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return gzip.compress(raw, mtime=0)


def decode(blob: bytes) -> dict:
    return json.loads(gzip.decompress(blob).decode("utf-8"))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)
    data = build()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(encode(data))
    print(json.dumps(data["manifest"]["counts"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
