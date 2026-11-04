"""후보를 API 명세 105(분석 결과 내부 저장)의 proposals[] 형식으로 바꾼다. JSON 필드는 camelCase.

105가 저장하는 칸은 itemKind·payload·evidence·aiConfidence·options뿐이다. 그래서 제안 이유, 근거 목록,
신뢰도 내역, 보류 권고처럼 검토 화면에 필요한 나머지는 evidence 객체 안에 넣는다.
"""

import hashlib
import json
import re

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
