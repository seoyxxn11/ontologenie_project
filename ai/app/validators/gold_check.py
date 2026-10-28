"""정답셋 폴더가 docs/dataset.md 6절 형식을 지키는지 점검한다. 문제 목록을 돌려주고, 비어 있으면 통과."""

import json
from pathlib import Path

from pydantic import ValidationError

from app.rules.transforms import TransformStep, apply_steps

FILES = [
    "manifest.json",
    "ontology.json",
    "mapping_rules.json",
    "id_links.json",
    "integrity_issues.json",
    "note_annotations.json",
]
LEVELS = {"must", "acceptable"}
CATEGORIES = {"master", "transaction", "detail", "history", "snapshot"}
KEY_TYPES = {"primary", "alternate", "none"}
DATA_TYPES = {
    "STRING",
    "INTEGER",
    "DECIMAL",
    "DATE",
    "TIME",
    "DATETIME",
    "BOOLEAN",
    "JSON",
}
CARDINALITIES = {"ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY"}
RULE_TYPES = {"same_value", "transform", "mapping_column", "name_alias"}
LINK_STATUSES = {
    "matched",
    "unmatched_expected",
    "unmatched_error",
    "missing",
    "ambiguous",
}
ISSUE_KINDS = {"unlinked", "duplicate", "constraint_violation"}
ENTITY_TYPES = {
    "EQUIPMENT",
    "OBSERVATION",
    "ACTION",
    "WORK_ORDER",
    "MAINTENANCE",
    "WORKER",
    "TIME",
    "LOT",
}
RESOLUTION_STATUSES = {"resolved", "ambiguous", "unmatched", "none"}
ASSERTIONS = {"affirmed", "negated", "uncertain"}


def check_gold(folder: Path) -> list[str]:
    folder = Path(folder)
    missing = [name for name in FILES if not (folder / name).exists()]
    if missing:
        return [f"파일 없음: {name}" for name in missing]
    data = {}
    problems = []
    for name in FILES:
        try:
            data[name] = json.loads((folder / name).read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            problems.append(f"{name}: JSON 형식 오류 ({error})")
    if problems:
        return problems

    ontology = data["ontology.json"]
    type_codes = {t["code"] for t in ontology["object_types"]}
    for t in ontology["object_types"]:
        _check_in(problems, f"object_type {t['code']}.level", t["level"], LEVELS)
        if "category" in t:
            _check_in(
                problems, f"object_type {t['code']}.category", t["category"], CATEGORIES
            )
    for p in ontology["properties"]:
        where = f"property {p['object_type']}.{p['code']}"
        _check_in(problems, f"{where}.object_type", p["object_type"], type_codes)
        _check_in(problems, f"{where}.data_type", p["data_type"], DATA_TYPES)
        _check_in(problems, f"{where}.level", p["level"], LEVELS)
    rule_ids = {r["rule_id"] for r in data["mapping_rules.json"]}
    for link in ontology["link_types"]:
        where = f"link_type {link['code']}"
        _check_in(problems, f"{where}.source_type", link["source_type"], type_codes)
        _check_in(problems, f"{where}.target_type", link["target_type"], type_codes)
        _check_in(problems, f"{where}.cardinality", link["cardinality"], CARDINALITIES)
        if link["mapping_rule"] is not None:
            _check_in(problems, f"{where}.mapping_rule", link["mapping_rule"], rule_ids)
        if link["derived_from"] == "column" and not link["via"]:
            problems.append(f"{where}: 컬럼 기반 관계에는 via가 필요합니다.")
        via = link.get("via") or {}
        if via:
            sides = [[s] if isinstance(s, str) else s for s in (via["from"], via["to"])]
            if len(sides[0]) != len(sides[1]):
                problems.append(f"{where}: 출발·도착 컬럼 수가 다릅니다.")
            _check_in(
                problems, f"{where}.via.key", via.get("key", "primary"), KEY_TYPES
            )
            if "condition" in via and set(via["condition"]) != {"column", "equals"}:
                problems.append(f"{where}: condition은 column·equals로 적습니다.")
            if "through" in via and set(via["through"]) != {"match", "take"}:
                problems.append(f"{where}: through는 match·take로 적습니다.")

    transforms_by_rule = {}
    for rule in data["mapping_rules.json"]:
        _check_in(
            problems,
            f"mapping_rule {rule['rule_id']}.rule_type",
            rule["rule_type"],
            RULE_TYPES,
        )
        if "reference_transforms" in rule:
            try:
                ref = rule["reference_transforms"]
                transforms_by_rule[rule["rule_id"]] = (
                    [TransformStep(**s) for s in ref["source_transforms"]],
                    [TransformStep(**s) for s in ref["target_transforms"]],
                )
            except (ValidationError, KeyError, TypeError) as error:
                problems.append(
                    f"mapping_rule {rule['rule_id']}: reference_transforms 형식 오류 ({error})"
                )

    for i, link in enumerate(data["id_links.json"]):
        where = f"id_links[{i}]"
        _check_in(problems, f"{where}.rule_id", link["rule_id"], rule_ids)
        _check_in(problems, f"{where}.status", link["status"], LINK_STATUSES)
        if link["status"] == "matched" and not link["to_value"]:
            problems.append(f"{where}: matched에는 to_value가 필요합니다.")
        if link["status"] == "ambiguous" and len(link["candidates"]) < 2:
            problems.append(f"{where}: ambiguous에는 후보가 2개 이상 필요합니다.")
        if link["status"] == "matched" and link["rule_id"] in transforms_by_rule:
            source_steps, target_steps = transforms_by_rule[link["rule_id"]]
            if apply_steps(source_steps, link["from_value"]) != apply_steps(
                target_steps, link["to_value"]
            ):
                problems.append(
                    f"{where}: 정답 변환 단계를 적용해도 '{link['from_value']}'와 '{link['to_value']}'가 같아지지 않습니다."
                )

    for issue in data["integrity_issues.json"]:
        _check_in(
            problems, f"integrity {issue['issue_id']}.kind", issue["kind"], ISSUE_KINDS
        )

    entity_count = 0
    for doc in data["note_annotations.json"]:
        problems.extend(_check_note(doc))
        entity_count += len(doc["entities"])

    counts = data["manifest.json"].get("counts", {})
    actual = {
        "object_types": len(ontology["object_types"]),
        "properties": len(ontology["properties"]),
        "link_types": len(ontology["link_types"]),
        "mapping_rules": len(data["mapping_rules.json"]),
        "id_links": len(data["id_links.json"]),
        "integrity_issues": len(data["integrity_issues.json"]),
        "note_documents": len(data["note_annotations.json"]),
        "note_entities": entity_count,
    }
    for key, value in counts.items():
        if actual.get(key) != value:
            problems.append(
                f"manifest counts.{key}={value} 이지만 실제 {actual.get(key)}건입니다."
            )
    return problems


def _check_note(doc: dict) -> list[str]:
    problems = []
    text = doc["text"]
    ids = [e["id"] for e in doc["entities"]]
    if len(ids) != len(set(ids)):
        problems.append(f"note {doc['doc_id']}: 개체 id가 중복됩니다.")
    for e in doc["entities"]:
        where = f"note {doc['doc_id']}.{e['id']}"
        _check_in(problems, f"{where}.type", e["type"], ENTITY_TYPES)
        _check_in(problems, f"{where}.assertion", e.get("assertion"), ASSERTIONS)
        if text[e["start"] : e["end"]] != e["text"]:
            problems.append(
                f"{where}: 위치 {e['start']}~{e['end']}의 글자가 '{e['text']}'와 다릅니다."
            )
        status = e["resolution"]["status"]
        _check_in(problems, f"{where}.resolution.status", status, RESOLUTION_STATUSES)
        if status == "resolved" and not e["resolution"]["ref"]:
            problems.append(f"{where}: resolved에는 ref가 필요합니다.")
        if status == "ambiguous" and len(e["resolution"]["candidates"]) < 2:
            problems.append(f"{where}: ambiguous에는 후보가 2개 이상 필요합니다.")
    for r in doc["relations"]:
        for end in (r["from"], r["to"]):
            if end != "doc" and end not in ids:
                problems.append(
                    f"note {doc['doc_id']}: 관계 {r['type']}가 없는 개체 {end}를 가리킵니다."
                )
    return problems


def _check_in(problems: list[str], where: str, value, allowed) -> None:
    if value not in allowed:
        problems.append(f"{where}: 허용되지 않은 값 '{value}'")


if __name__ == "__main__":
    import sys

    found = check_gold(Path(sys.argv[1]))
    print("\n".join(found) if found else "정답셋 형식 점검 통과")
    sys.exit(1 if found else 0)
