import pytest
from pydantic import ValidationError

from app.models.extraction import (
    Assertion,
    DocumentExtraction,
    EntityType,
    ExtractedEntity,
    ExtractedRelation,
    Resolution,
    ResolutionStatus,
)

TEXT = "[8/25 주간 → 8/25 야간 인수인계] 작성 44\n1라인 1호 프레스 진동 이상 없음.\n"


def _entity(eid, etype, needle, **kwargs):
    start = TEXT.index(needle)
    return ExtractedEntity(
        id=eid, type=etype, start=start, end=start + len(needle), text=needle, **kwargs
    )


def test_negated_observation_keeps_its_assertion():
    entity = _entity(
        "e2", EntityType.OBSERVATION, "진동 이상 없음", assertion=Assertion.NEGATED
    )

    assert entity.assertion == Assertion.NEGATED


def test_span_length_must_match_text():
    with pytest.raises(ValidationError):
        ExtractedEntity(
            id="e1", type=EntityType.EQUIPMENT, start=0, end=3, text="1라인 1호 프레스"
        )


def test_resolution_rules():
    assert (
        Resolution(status=ResolutionStatus.RESOLVED, ref="mes.equipment:P101").ref
        == "mes.equipment:P101"
    )
    with pytest.raises(ValidationError):
        Resolution(status=ResolutionStatus.RESOLVED)
    with pytest.raises(ValidationError):
        Resolution(status=ResolutionStatus.AMBIGUOUS, candidates=["mes.equipment:P103"])


def test_check_against_source_text_finds_wrong_position():
    good = _entity("e1", EntityType.EQUIPMENT, "1라인 1호 프레스")
    wrong = ExtractedEntity(
        id="e2", type=EntityType.OBSERVATION, start=0, end=4, text="진동 이"
    )
    extraction = DocumentExtraction(
        doc_id="HN-260825-D", source_kind="note", entities=[good, wrong]
    )

    assert extraction.check_against(TEXT) == [
        "e2: 위치 0~4의 원문이 '진동 이'와 다릅니다."
    ]


def test_relation_must_point_to_existing_entity():
    entity = _entity("e1", EntityType.EQUIPMENT, "1라인 1호 프레스")
    DocumentExtraction(
        doc_id="d",
        source_kind="note",
        entities=[entity],
        relations=[
            ExtractedRelation(type="mentions_equipment", source="doc", target="e1")
        ],
    )
    with pytest.raises(ValidationError):
        DocumentExtraction(
            doc_id="d",
            source_kind="note",
            entities=[entity],
            relations=[ExtractedRelation(type="observed_on", source="e9", target="e1")],
        )
