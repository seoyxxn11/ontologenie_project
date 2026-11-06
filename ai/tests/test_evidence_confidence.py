import pytest

from app.dataset.build import generate
from app.models.candidate import Candidate, Evidence
from app.proposers import confidence
from app.proposers.evidence import SourceLookup, verify


@pytest.fixture(scope="module")
def data(tmp_path_factory):
    root = tmp_path_factory.mktemp("data")
    generate("t3", root=root)
    return root / "data-t3"


def test_evidence_must_exist_in_source(data):
    lookup = SourceLookup(data)
    assert lookup.check(
        Evidence(
            source_id="mes", locator="mes.equipment.code", snippet="", values=["P103"]
        )
    )
    assert not lookup.check(
        Evidence(
            source_id="mes", locator="mes.equipment.code", snippet="", values=["P999"]
        )
    )
    assert not lookup.check(
        Evidence(source_id="mes", locator="mes.no_table", snippet="")
    )
    assert not lookup.check(
        Evidence(source_id="erp", locator="mes.equipment", snippet="")
    )


def _candidate(evidence):
    return Candidate.model_validate(
        {
            "candidate_id": "OT-mes.equipment",
            "title": "설비",
            "reason": "기본키가 있는 표",
            "payload": {"kind": "OBJECT_TYPE", "code": "EQUIPMENT", "name": "설비"},
            "evidence": evidence,
        }
    )


def test_candidates_without_verified_evidence_are_dropped(data):
    real = _candidate(
        [{"source_id": "mes", "locator": "mes.equipment.code", "snippet": "설비 코드"}]
    )
    made_up = _candidate(
        [{"source_id": "erp", "locator": "erp.nothing", "snippet": "지어낸 근거"}]
    )

    kept, dropped = verify(data, [real, made_up])

    assert [c.evidence[0].snippet for c in kept] == ["설비 코드"]
    assert (
        kept[0].evidence[0].verified is True
    )  # 원천에서 다시 확인한 근거라고 표시한다
    assert [c.evidence[0].snippet for c in dropped] == ["지어낸 근거"]


def test_link_score_adds_value_key_and_name_up_to_0_8():
    full = confidence.build(confidence.link(1.0, 1.0, "primary", name=True))
    assert full.total == 0.8
    # 값 포함은 고유값·행 기준 중 낮은 쪽만 본다
    half = confidence.build(confidence.link(0.5, 1.0, "none"))
    assert half.total == 0.25
    assert [item.label for item in half.items] == [
        "값 포함 50%"
    ]  # 0점 신호는 빼고 보여 준다


def test_hold_caps_score_at_low_band():
    held = confidence.build(
        confidence.link(1.0, 1.0, "primary", name=True), hold="번호만 같은 순번 키"
    )
    assert held.total == confidence.CAP
    assert held.cap_reason == "번호만 같은 순번 키"
    assert confidence.band(held.total) == "낮음"


@pytest.mark.parametrize(
    "score, band", [(None, "미산출"), (0.8, "높음"), (0.5, "보통"), (0.49, "낮음")]
)
def test_score_bands(score, band):
    assert confidence.band(score) == band


def test_document_link_evidence_checks_document_still_exists(data):
    # 문서 연결 근거는 글 컬럼 위치와 함께 문서 번호를 원천에서 다시 찾는다
    lookup = SourceLookup(data)
    note_id = SourceLookup(data)._reader("mes").read_rows("handover_note")["note_id"][0]

    def doc(value):
        return Evidence(
            source_id="mes",
            locator="mes.handover_note.note_id",
            snippet="문서 번호",
            values=[value],
        )

    assert lookup.check(doc(note_id))
    assert not lookup.check(doc("HN-없는-문서"))
