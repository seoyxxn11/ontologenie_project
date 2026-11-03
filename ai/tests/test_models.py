import pytest
from pydantic import ValidationError

from app.models.candidate import (
    Candidate,
    CandidateOption,
    Confidence,
    DataType,
    DocumentLinkPayload,
    Evidence,
    ItemKind,
    LinkRuleCondition,
    LinkRulePayload,
    ObjectTypePayload,
    PropertyPayload,
    RuleType,
    ScoreItem,
)
from app.rules.transforms import TransformOp, TransformStep

STRIP_W = [
    TransformStep(op=TransformOp.STRIP_PREFIX, arg="W"),
    TransformStep(op=TransformOp.PAD_ZEROS, arg=3),
    TransformStep(op=TransformOp.ADD_PREFIX, arg="E"),
]


def _evidence(snippet="W11"):
    return [Evidence(source_id="mes", locator="worker.worker_id", snippet=snippet)]


def _rule(**overrides):
    values = {
        "code": "WORKER_TO_EMPLOYEE",
        "link_type_code": "WORKER_EMPLOYEE",
        "pattern": RuleType.TRANSFORM,
        "conditions": [
            LinkRuleCondition(
                source_property="WORKER.worker_id",
                target_property="EMPLOYEE.emp_no",
                source_transforms=STRIP_W,
            )
        ],
    }
    values.update(overrides)
    return LinkRulePayload(**values)


def _candidate(**overrides):
    values = {
        "candidate_id": "C-1",
        "title": "작업자 번호 → 직원 번호",
        "payload": _rule(),
        "reason": "작업자 번호와 사번이 접두어·자릿수만 다름",
        "evidence": _evidence(),
    }
    values.update(overrides)
    return Candidate(**values)


def _memo_link(target, option_no=None):
    payload = DocumentLinkPayload(
        link_type_code="NOTE_MENTIONS_EQUIPMENT",
        document_id="HN-260821-D",
        span_start=31,
        span_end=34,
        mention_text="3호기",
        target_object_ref=target,
    )
    if option_no is None:
        return payload
    return CandidateOption(
        option_no=option_no, payload=payload, evidence=_evidence("3호기")
    )


def test_confidence_is_zero_to_one_with_breakdown():
    confidence = Confidence(
        items=[
            ScoreItem(label="형식 유사", weight=0.45),
            ScoreItem(label="이름·주석 의미", weight=0.3),
        ]
    )

    assert confidence.total == 0.75
    assert confidence.describe() == "형식 유사 0.45 + 이름·주석 의미 0.3 = 0.75"
    with pytest.raises(ValidationError):
        Confidence(
            items=[ScoreItem(label="a", weight=0.6), ScoreItem(label="b", weight=0.5)]
        )
    with pytest.raises(ValidationError):
        ScoreItem(label="a", weight=45)


def test_erd_value_sets():
    assert {d.value for d in DataType} == {
        "STRING",
        "INTEGER",
        "DECIMAL",
        "DATE",
        "TIME",
        "DATETIME",
        "BOOLEAN",
        "JSON",
    }
    with pytest.raises(ValidationError):
        PropertyPayload(
            object_type_code="EQUIPMENT",
            code="tonnage",
            name="톤수",
            data_type="number",
        )
    assert {k.value for k in ItemKind} == {
        "OBJECT_TYPE",
        "PROPERTY",
        "LINK_TYPE",
        "OBJECT_MAPPING",
        "PROPERTY_MAPPING",
        "LINK_RULE",
        "DOCUMENT_LINK",
    }


def test_link_rule_pattern_must_match_transforms():
    with pytest.raises(ValidationError):
        _rule(
            conditions=[LinkRuleCondition(source_property="A.x", target_property="B.y")]
        )
    same = _rule(
        pattern=RuleType.SAME_VALUE,
        conditions=[LinkRuleCondition(source_property="A.x", target_property="B.y")],
    )
    assert same.conditions[0].operator == "EQ"
    with pytest.raises(ValidationError):
        _rule(pattern=RuleType.SAME_VALUE)


def test_candidate_requires_reason_and_evidence():
    with pytest.raises(ValidationError):
        _candidate(reason="")
    with pytest.raises(ValidationError):
        _candidate(evidence=[])


def test_candidate_payload_is_parsed_from_dict_by_kind():
    candidate = Candidate.model_validate(
        {
            "candidate_id": "C-2",
            "title": "설비 유형",
            "payload": {
                "kind": "OBJECT_TYPE",
                "code": "EQUIPMENT",
                "name": "설비",
                "synonyms": ["프레스", "호기"],
            },
            "reason": "설비 코드가 기본키이고 작업지시가 이 코드를 가리킴",
            "evidence": [
                {"source_id": "mes", "locator": "equipment", "snippet": "equip_code"}
            ],
            "confidence": {"items": [{"label": "기본키 존재", "weight": 0.25}]},
        }
    )

    assert isinstance(candidate.payload, ObjectTypePayload)
    assert candidate.ai_confidence == 0.25


def test_ambiguous_memo_mention_uses_options():
    candidate = _candidate(
        title="메모 '3호기' → 설비",
        payload=_memo_link("mes.equipment:P103"),
        options=[
            _memo_link("mes.equipment:P103", 1),
            _memo_link("mes.equipment:P203", 2),
        ],
        reason="1라인·2라인 모두 3호 프레스가 있어 하나로 정할 수 없음",
        evidence=_evidence("3호기"),
    )

    assert [o.payload.target_object_ref for o in candidate.options] == [
        "mes.equipment:P103",
        "mes.equipment:P203",
    ]


def test_options_rules():
    with pytest.raises(ValidationError):
        _candidate(payload=_memo_link("x"), options=[_memo_link("x", 1)])
    with pytest.raises(ValidationError):
        _candidate(
            payload=_memo_link("x"), options=[_memo_link("x", 1), _memo_link("y", 1)]
        )
    with pytest.raises(ValidationError):
        _candidate(options=[_memo_link("x", 1), _memo_link("y", 2)])


def test_document_link_span_length_checked():
    with pytest.raises(ValidationError):
        DocumentLinkPayload(
            link_type_code="L",
            document_id="d",
            span_start=0,
            span_end=10,
            mention_text="3호기",
            target_object_ref="x",
        )
