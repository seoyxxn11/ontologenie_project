"""후보를 API 명세 105(분석 결과 내부 저장)의 proposals[] 형식으로 바꾼다. JSON 필드는 camelCase.

105가 저장하는 칸은 itemKind·payload·evidence·aiConfidence·options뿐이다. 그래서 제안 이유, 근거 목록,
신뢰도 내역, 보류 권고처럼 검토 화면에 필요한 나머지는 evidence 객체 안에 넣는다.

제안기 안에서는 컬럼 하나하나를 후보로 다루지만(채점·근거 확인 단위), 105로 보낼 때는
to_api_proposals가 표 하나·관계 하나 단위로 묶는다. 관리자가 판단하는 단위가 객체와 관계이기 때문이다.
"""

import hashlib
import json
import re
from collections import Counter

from app.models.candidate import Candidate


def camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in rest)


def camel_keys(value):
    if isinstance(value, dict):
        return {camel(k): camel_keys(v) for k, v in value.items()}
    if isinstance(value, list):
        return [camel_keys(v) for v in value]
    return value


def fingerprint_payload(api_payload: dict) -> str:
    """연동 형식(camelCase) payload의 지문. 키 순서와 무관하다. 재분석 비교·과거 결정 대조에 쓴다."""
    text = json.dumps(api_payload, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def api_payload(data: dict) -> dict:
    """후보 payload(model_dump) → 연동 형식. 지문도 이 결과로 계산한다."""
    return camel_keys({k: v for k, v in data.items() if k != "kind"})


def to_api(candidate: Candidate) -> dict:
    data = candidate.model_dump(mode="json")
    payload = api_payload(data["payload"])
    confidence = candidate.confidence
    evidence = {
        "candidateId": candidate.candidate_id,  # 재분석 비교·과거 결정 대조용
        "fingerprint": fingerprint_payload(payload),
        "title": candidate.title,
        "reason": candidate.reason,
        "items": camel_keys(data["evidence"]),
        "confidenceItems": camel_keys(data["confidence"]["items"])
        if confidence
        else [],
        "confidenceCap": camel_keys(
            {"cap": confidence.cap, "reason": confidence.cap_reason}
        )
        if confidence and confidence.cap is not None
        else None,
        "method": candidate.method,
        # 105는 검토 상태를 PENDING으로 저장한다. AI의 보류 의견은 권고로만 남긴다
        "holdRecommended": candidate.review_status == "HELD",
        "holdReason": candidate.hold_reason,
        "llmJudgement": candidate.llm_judgement,
    }
    if candidate.document is not None:
        evidence["originalText"] = candidate.document.original_text
        evidence["observations"] = camel_keys(data["document"]["observations"])
        evidence["times"] = camel_keys(data["document"]["times"])
    return {
        "itemKind": candidate.item_kind.value,
        "payload": payload,
        "evidence": evidence,
        "aiConfidence": candidate.ai_confidence,
        "options": [
            {
                "optionNo": o["option_no"],
                "payload": api_payload(o["payload"]),
                "evidence": {"items": camel_keys(o["evidence"])},
                "aiConfidence": opt.confidence.total if opt.confidence else None,
            }
            for o, opt in zip(data["options"], candidate.options, strict=True)
        ],
    }


def _held(*candidates: Candidate) -> tuple[bool, str | None]:
    """묶음 안에 보류 권고가 하나라도 있으면 묶음도 보류 권고. 이유는 겹치지 않게 잇는다."""
    held = [c for c in candidates if c.review_status == "HELD"]
    if not held:
        return False, None
    reasons = dict.fromkeys(c.hold_reason for c in held if c.hold_reason)
    return True, " / ".join(reasons) or None


def _part_evidence(api: dict) -> dict:
    """묶음 안 구성 후보의 근거. 지문·후보 ID처럼 묶음 단위로 다시 정하는 값만 뺀다.
    보류 권고·이유·신뢰도 상한은 남겨 어느 속성 때문에 묶음이 보류 권고인지 보이게 한다."""
    keep = (
        "title",
        "reason",
        "items",
        "confidenceItems",
        "confidenceCap",
        "method",
        "llmJudgement",
        "holdRecommended",
        "holdReason",
    )
    return {k: api["evidence"][k] for k in keep}


def _without(payload: dict, key: str) -> dict:
    return {k: v for k, v in payload.items() if k != key}


def _object_bundle(
    head: Candidate,
    mappings: list[Candidate],
    properties: list[Candidate],
    property_mappings: dict[str, Candidate],
) -> dict:
    """객체 유형 하나에 속성·원천 매핑을 담은 OBJECT_TYPE 후보 (표 하나 = 검토 한 건)."""
    out = to_api(head)
    payload, evidence = out["payload"], out["evidence"]
    if mappings:
        mapping = to_api(mappings[0])
        payload["objectMapping"] = _without(mapping["payload"], "objectTypeCode")
        evidence["objectMapping"] = _part_evidence(mapping)
    payload["properties"], evidence["properties"] = [], []
    for prop in properties:
        api = to_api(prop)
        item = _without(api["payload"], "objectTypeCode")
        part = {"code": item["code"], **_part_evidence(api)}
        source = property_mappings.get(prop.payload.code)
        if source is not None:
            mapped = to_api(source)
            item["sourceColumn"] = mapped["payload"]["sourceColumn"]
            item["transformSteps"] = mapped["payload"]["transformSteps"]
            part["mapping"] = _part_evidence(mapped)
        payload["properties"].append(item)
        evidence["properties"].append(part)
    held, reason = _held(head, *mappings, *properties, *property_mappings.values())
    evidence["holdRecommended"], evidence["holdReason"] = held, reason
    evidence["fingerprint"] = fingerprint_payload(payload)
    return out


def _link_bundle(rule: Candidate, link_type: Candidate) -> dict:
    """관계 유형과 그 연결 규칙을 담은 LINK_RULE 후보 (관계 하나 = 검토 한 건)."""
    out = to_api(rule)
    out["payload"]["linkType"] = to_api(link_type)["payload"]
    held, reason = _held(rule, link_type)
    out["evidence"]["holdRecommended"], out["evidence"]["holdReason"] = held, reason
    out["evidence"]["fingerprint"] = fingerprint_payload(out["payload"])
    return out


def to_api_proposals(candidates: list[Candidate]) -> list[dict]:
    """후보 목록 → 105 proposals[]. 관리자가 판단하는 단위로 묶는다.

    - 객체 유형 + 객체 매핑 + 속성 + 속성 매핑 → OBJECT_TYPE 한 건 (payload.objectMapping·properties)
    - 관계 유형 + 그 연결 규칙 → LINK_RULE 한 건 (payload.linkType)
    - 연결 규칙이 없는 관계 유형(문서 연결용, 규칙으로 못 쓰는 관계)과 문서 연결은 그대로 한 건씩
    묶을 짝이 없는 구성 후보(예: 객체 유형 후보가 없는 속성)는 원래 종류 그대로 따로 보낸다.
    """
    groups: dict[str, list[Candidate]] = {}
    for c in candidates:
        groups.setdefault(c.item_kind.value, []).append(c)
    loose: list[Candidate] = []
    # 코드가 겹치는 객체 유형은 어느 쪽에 묶을지 모호하므로 묶지 않고 따로 보낸다(잃지 않는다).
    codes = Counter(c.payload.code for c in groups.get("OBJECT_TYPE", []))
    heads: dict[str, Candidate] = {}
    for c in groups.get("OBJECT_TYPE", []):
        if codes[c.payload.code] == 1:
            heads[c.payload.code] = c
        else:
            loose.append(c)
    mappings: dict[str, list[Candidate]] = {}
    properties: dict[str, dict[str, Candidate]] = {}
    property_mappings: dict[str, dict[str, Candidate]] = {}
    for c in groups.get("OBJECT_MAPPING", []):
        owner = c.payload.object_type_code
        # 객체 매핑은 묶음에 하나만 담는다. 둘째부터는 따로 보낸다.
        if owner in heads and owner not in mappings:
            mappings[owner] = [c]
        else:
            loose.append(c)
    for c in groups.get("PROPERTY", []):
        owner, code = c.payload.object_type_code, c.payload.code
        if owner in heads and code not in properties.get(owner, {}):
            properties.setdefault(owner, {})[code] = c
        else:
            loose.append(c)
    for c in groups.get("PROPERTY_MAPPING", []):
        owner, code = c.payload.object_type_code, c.payload.property_code
        if code in properties.get(owner, {}) and code not in property_mappings.get(
            owner, {}
        ):
            property_mappings.setdefault(owner, {})[code] = c
        else:
            loose.append(c)
    out = [
        _object_bundle(
            head,
            mappings.get(code, []),
            list(properties.get(code, {}).values()),
            property_mappings.get(code, {}),
        )
        for code, head in heads.items()
    ]
    types: dict[str, Candidate] = {}
    for c in groups.get("LINK_TYPE", []):
        if c.payload.code in types:
            loose.append(c)
        else:
            types[c.payload.code] = c
    paired: set[str] = set()
    for rule in groups.get("LINK_RULE", []):
        code = rule.payload.link_type_code
        if code in types:
            # 같은 관계 유형을 쓰는 규칙이 여럿이어도 규칙마다 관계 유형을 담는다
            paired.add(code)
            out.append(_link_bundle(rule, types[code]))
        else:
            out.append(to_api(rule))
    out += [to_api(t) for code, t in types.items() if code not in paired]
    out += [to_api(c) for c in loose + groups.get("DOCUMENT_LINK", [])]
    return out


SNAKE = re.compile(r"_[a-z]")


def has_snake_keys(value) -> bool:
    """연동 JSON에 snake_case 키가 남았는지 (테스트용)."""
    if isinstance(value, dict):
        return any(SNAKE.search(k) for k in value) or any(
            has_snake_keys(v) for v in value.values()
        )
    if isinstance(value, list):
        return any(has_snake_keys(v) for v in value)
    return False
