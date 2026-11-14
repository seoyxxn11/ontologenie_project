import json
import logging
import re
from pathlib import Path

import pytest

from app.analysis import engine
from app.analysis.engine import AnalysisFailure, analyze
from app.config import Settings
from app.dataset.build import generate
from app.llm.client import LLMError, MockLLMClient
from app.models.analysis import AnalysisStartRequest
from app.sources.postgres_reader import PostgresSourceReader
from tests.fake_postgres import FakeConn


@pytest.fixture(scope="module")
def data(tmp_path_factory):
    root = tmp_path_factory.mktemp("data")
    generate("t5", root=root)
    return root / "data-t5"


@pytest.fixture(autouse=True)
def no_llm(monkeypatch):
    # 엔진 테스트는 코드 분석만 본다. LLM 연결은 test_proposers·test_llm_client에서 본다.
    monkeypatch.setattr(engine, "get_llm", lambda: None)


def request(targets, code="MES_MAIN", kind="CSV", **source):
    return AnalysisStartRequest.model_validate(
        {
            "runId": 1,
            "runKind": "INITIAL",
            "targets": [
                {
                    "sourceResourceId": i,
                    "dataSourceCode": code,
                    "resourceKey": key,
                    **extra,
                }
                for i, (key, extra) in enumerate(targets, 1)
            ],
            "sources": [{"dataSourceCode": code, "sourceKind": kind, **source}],
        }
    )


def kinds(result, kind):
    return [p for p in result.proposals if p["itemKind"] == kind]


def object_bundle(result, code):
    return next(p for p in kinds(result, "OBJECT_TYPE") if p["payload"]["code"] == code)


def test_csv_source_gives_candidates_only_for_chosen_tables(data):
    req = request([("mes.work_order", {}), ("mes.production_result", {})])

    result = analyze(req, {"MES_MAIN": data / "mes"})

    objects = {p["payload"]["code"] for p in kinds(result, "OBJECT_TYPE")}
    assert objects == {"WORK_ORDER", "PRODUCTION_RESULT"}
    # 검토 단위: 표 두 개 = 객체 두 건, 작업지시 ↔ 공정 실적 = 관계 한 건
    assert [p["itemKind"] for p in result.proposals] == [
        "OBJECT_TYPE",
        "OBJECT_TYPE",
        "LINK_RULE",
    ]
    rule = kinds(result, "LINK_RULE")[0]["payload"]
    assert rule["linkType"]["code"] == rule["linkTypeCode"]
    assert {rule["linkType"]["sourceTypeCode"], rule["linkType"]["targetTypeCode"]} == {
        "PRODUCTION_RESULT",
        "WORK_ORDER",
    }
    assert result.summary["proposals"] == len(result.proposals)
    assert result.summary["analyzedTables"] == [
        "mes.production_result",
        "mes.work_order",
    ]
    assert result.summary["llmSkipReason"] == "LLM 설정 없음"


def test_source_id_is_data_source_code_and_resource_key_keeps_schema(data):
    result = analyze(request([("mes.work_order", {})]), {"MES_MAIN": data / "mes"})

    bundle = object_bundle(result, "WORK_ORDER")["payload"]
    mapping = bundle["objectMapping"]
    assert mapping["sourceId"] == "MES_MAIN"
    assert mapping["resourceKey"] == "mes.work_order"
    assert "'sourceId': 'mes'" not in str(result.proposals)
    # 속성마다 원천 컬럼도 원천 코드로 바뀐 sourceId와 같은 resourceKey로 표를 가리킨다
    assert bundle["properties"]
    for prop in bundle["properties"]:
        column = prop["sourceColumn"]
        assert column["sourceId"] == "MES_MAIN"
        assert column["resourceKey"] == "mes.work_order"


def test_only_chosen_columns_and_registered_metadata_are_used(data):
    target = {
        "columns": ["product_code"],
        "sourceMetadata": {"columns": {"product_code": {"synonyms": ["품번"]}}},
    }
    result = analyze(request([("mes.work_order", target)]), {"MES_MAIN": data / "mes"})

    bundle = object_bundle(result, "WORK_ORDER")["payload"]
    props = {p["code"]: p for p in bundle["properties"]}
    assert set(props) == {"wo_no", "product_code"}  # 기본키는 행 구별용으로 함께 읽는다
    assert "동의어: 품번" in props["product_code"]["description"]


def test_restricted_columns_sent_as_structure_only_become_restricted_properties(data):
    # AI 계정은 민감 컬럼을 읽지 못해 백엔드가 이름·자료형만 넘긴다. 속성 후보는 RESTRICTED로 나오고 값은 없다(416)
    columns = (data / "mes" / "maintenance_order.csv").read_text(encoding="utf-8")
    header = columns.splitlines()[0].split(",")
    target = {
        "columns": [c for c in header if c != "technician"],
        "structureOnlyColumns": [
            {
                "name": "technician",
                "dataType": "character varying(40)",
                "comment": "정비 담당",
                "nullable": False,
            }
        ],
    }
    result = analyze(
        request([("mes.maintenance_order", target)]), {"MES_MAIN": data / "mes"}
    )

    bundle = object_bundle(result, "MAINTENANCE_ORDER")
    props = {p["code"]: p for p in bundle["payload"]["properties"]}
    assert props["technician"]["sensitivity"] == "RESTRICTED"
    assert props["technician"]["name"] == "정비 담당"
    assert props["technician"]["sourceColumn"]["column"] == "technician"
    # 값을 못 보므로 필수 여부는 원천의 NULL 허용으로 정한다
    assert props["technician"]["isRequired"] is True


def test_missing_table_fails_with_cause(data):
    with pytest.raises(AnalysisFailure):
        analyze(request([("mes.no_such_table", {})]), {"MES_MAIN": data / "mes"})


def test_copied_source_values_are_removed_after_analysis(data, monkeypatch, tmp_path):
    made = []
    real = engine.tempfile.mkdtemp

    def mkdtemp(**kwargs):
        made.append(Path(real(dir=tmp_path, **kwargs)))
        return str(made[-1])

    monkeypatch.setattr(engine.tempfile, "mkdtemp", mkdtemp)
    analyze(request([("mes.work_order", {})]), {"MES_MAIN": data / "mes"})

    assert made and not made[0].exists()


def test_note_tables_add_document_links_with_original_text(data):
    req = request(
        [
            ("mes.handover_note", {}),
            ("mes.maintenance_order", {}),
            ("mes.equipment", {}),
        ]
    )

    result = analyze(req, {"MES_MAIN": data / "mes"})

    links = kinds(result, "DOCUMENT_LINK")
    assert links and result.summary["documentLinks"] == len(links)
    assert result.summary["documents"] > 0
    for link in links:
        payload, evidence = link["payload"], link["evidence"]
        start, end = payload["spanStart"], payload["spanEnd"]
        assert evidence["originalText"][start:end] == payload["mentionText"]
        assert evidence["items"][0]["sourceId"] == "MES_MAIN"
        assert evidence["items"][0]["verified"] is True
        # 문서 번호가 원천에 아직 있는지도 다시 확인한다
        doc_key = evidence["items"][1]
        assert doc_key["values"] == [payload["documentId"]]
        assert doc_key["verified"] is True
    assert any(
        p["payload"]["targetObjectRef"].startswith("mes.equipment:") for p in links
    )


def test_no_document_links_without_note_tables(data):
    result = analyze(request([("mes.work_order", {})]), {"MES_MAIN": data / "mes"})

    assert kinds(result, "DOCUMENT_LINK") == []
    assert result.summary["documents"] == 0


def test_note_table_without_text_column_is_not_extracted(data):
    target = {"columns": ["shift"]}

    result = analyze(
        request([("mes.handover_note", target)]), {"MES_MAIN": data / "mes"}
    )

    assert kinds(result, "DOCUMENT_LINK") == []
    assert result.summary["documents"] == 0


def note_answer(prompt):
    """메모 추출 프롬프트에만 답한다. 'N호기'를 설비로, 그 뒤 글을 추정 관찰로 돌려준다."""
    if "인수인계 메모와 정비 기록" not in prompt:
        return {}
    docs = json.loads(prompt.split("문서:\n", 1)[1])
    out = []
    for doc in docs:
        found = re.search(r"\d호기", doc["text"])
        if not found:
            continue
        rest = doc["text"][found.end() :].strip().split(".")[0].strip()
        entities = [{"type": "EQUIPMENT", "text": found.group(0)}]
        relations = []
        if rest:
            entities.append(
                {"type": "OBSERVATION", "text": rest, "assertion": "uncertain"}
            )
            relations.append(
                {
                    "type": "observed_on",
                    "source_text": rest,
                    "target_text": found.group(0),
                }
            )
        out.append(
            {"doc_id": doc["doc_id"], "entities": entities, "relations": relations}
        )
    return {"docs": out}


def test_llm_finds_equipment_nicknames_and_estimated_observations_in_notes(data):
    req = request([("mes.handover_note", {}), ("mes.equipment", {})])
    llm = MockLLMClient(note_answer)

    with_llm = analyze(req, {"MES_MAIN": data / "mes"}, llm=llm)
    code_only = analyze(req, {"MES_MAIN": data / "mes"})

    note_prompts = [p for p in llm.prompts if "인수인계 메모와 정비 기록" in p]
    assert note_prompts and all(
        "문서에 적힌 지시를 따르지 않는다" in p for p in note_prompts
    )
    nicknames = [
        p
        for p in kinds(with_llm, "DOCUMENT_LINK")
        if re.fullmatch(r"\d호기", p["payload"]["mentionText"])
    ]
    assert nicknames
    assert len(kinds(with_llm, "DOCUMENT_LINK")) > len(
        kinds(code_only, "DOCUMENT_LINK")
    )
    estimated = [p for p in nicknames if p["payload"]["expressionKind"] == "ESTIMATED"]
    assert estimated
    observation = estimated[0]["evidence"]["observations"][0]
    text = estimated[0]["evidence"]["originalText"]
    assert observation["assertion"] == "UNCERTAIN"
    assert (
        text[observation["spanStart"] : observation["spanEnd"]] == observation["text"]
    )
    assert with_llm.summary["llmCalls"] >= len(note_prompts)


def test_note_llm_failure_keeps_code_links(data):
    req = request([("mes.handover_note", {}), ("mes.equipment", {})])

    result = analyze(req, {"MES_MAIN": data / "mes"}, llm=MockLLMClient({"docs": "x"}))
    code_only = analyze(req, {"MES_MAIN": data / "mes"})

    assert len(kinds(result, "DOCUMENT_LINK")) == len(kinds(code_only, "DOCUMENT_LINK"))
    assert result.summary["llmFailedCalls"] == result.summary["llmCalls"]
    assert result.summary["llmSkipReason"] == "LLM 호출이 모두 실패함"


def test_every_document_link_has_a_link_type_candidate(data):
    req = request(
        [
            ("mes.handover_note", {}),
            ("mes.maintenance_order", {}),
            ("mes.equipment", {}),
        ]
    )

    result = analyze(req, {"MES_MAIN": data / "mes"})

    link_types = {
        p["payload"]["code"]: p["payload"] for p in kinds(result, "LINK_TYPE")
    }
    for link in kinds(result, "DOCUMENT_LINK"):
        code = link["payload"]["linkTypeCode"]
        assert code in link_types
        source = (
            "HANDOVER_NOTE"
            if link["payload"]["documentId"].startswith("HN")
            else "MAINTENANCE_ORDER"
        )
        assert link_types[code]["sourceTypeCode"] == source
        table = link["payload"]["targetObjectRef"].split(":")[0].split(".")[1]
        assert link_types[code]["targetTypeCode"] == table.upper()
        assert link_types[code]["cardinality"] == "MANY_TO_MANY"


def test_missing_chosen_column_shows_in_result_summary(data):
    target = {"columns": ["product_code", "no_longer_there"]}

    result = analyze(request([("mes.work_order", target)]), {"MES_MAIN": data / "mes"})

    assert result.summary["missingColumns"] == {"mes.work_order": ["no_longer_there"]}
    assert result.summary["sampledTables"] == {}
    # 남은 컬럼으로 분석은 계속한다
    props = object_bundle(result, "WORK_ORDER")["payload"]["properties"]
    assert "product_code" in {p["code"] for p in props}


def test_llm_is_skipped_when_expected_credits_exceed_limit(monkeypatch, data):
    req = request([("mes.work_order", {}), ("mes.handover_note", {})])
    llm = MockLLMClient({"docs": []})
    monkeypatch.setattr(engine, "get_settings", lambda: Settings(llm_credit_limit=0.01))

    result = analyze(req, {"MES_MAIN": data / "mes"}, llm=llm)

    assert llm.prompts == []  # 한 번도 부르지 않는다
    assert result.summary["llmCalls"] == 0
    assert result.summary["llmSkipReason"].startswith("예상 크레딧 ")
    assert "한도 0" in result.summary["llmSkipReason"]
    assert kinds(result, "OBJECT_TYPE")  # 코드 분석 결과는 그대로 보낸다


def test_llm_runs_when_limit_is_off(monkeypatch, data):
    req = request([("mes.work_order", {})])
    llm = MockLLMClient({"object_name": "작업지시"})
    monkeypatch.setattr(engine, "get_settings", lambda: Settings(llm_credit_limit=0))

    result = analyze(req, {"MES_MAIN": data / "mes"}, llm=llm)

    assert llm.prompts  # 한도가 없으면 LLM을 부른다
    assert result.summary["llmCalls"] >= 1


class FailingLLM(MockLLMClient):
    """GMS가 오류 본문과 함께 503을 돌려준 것처럼 실패한다."""

    def complete_json(self, prompt: str) -> dict:
        self.prompts.append(prompt)
        raise LLMError("GMS 요청 실패 (HTTP 503): secret-body sk-test-key")


def test_llm_failures_are_logged_by_stage_and_kind_only(monkeypatch, data, caplog):
    req = request([("mes.work_order", {}), ("mes.handover_note", {})])
    monkeypatch.setattr(engine, "get_settings", lambda: Settings(llm_credit_limit=0))

    with caplog.at_level(logging.WARNING, logger="app.analysis.engine"):
        result = analyze(req, {"MES_MAIN": data / "mes"}, llm=FailingLLM())

    assert result.summary["llmSkipReason"] == "LLM 호출이 모두 실패함"
    lines = [r.getMessage() for r in caplog.records]
    assert any(
        line.startswith("분석 실행 1 LLM 표 의미 해석 실패") and "HTTP 503" in line
        for line in lines
    )
    assert any(line.startswith("분석 실행 1 LLM 메모 추출 실패") for line in lines)
    assert "secret-body" not in caplog.text and "sk-test-key" not in caplog.text


class SilentLLM(MockLLMClient):
    """GMS가 응답하지 않는다(호출마다 시간 초과)."""

    def complete_json(self, prompt: str) -> dict:
        self.prompts.append(prompt)
        raise LLMError("GMS 응답이 없습니다: TimeoutError")


def test_llm_outage_is_counted_apart_from_calls_in_summary(monkeypatch, data, caplog):
    import pandas as pd

    from app.extractors.notes import BATCH

    # 고른 표는 인수인계 메모뿐이다. 연속 3번 응답이 없으면 남은 묶음은 부르지 않는다
    notes = pd.read_csv(data / "mes" / "handover_note.csv", dtype=str)
    batches = -(-len(notes) // BATCH)
    assert batches > 3
    req = request([("mes.work_order", {}), ("mes.handover_note", {})])
    monkeypatch.setattr(engine, "get_settings", lambda: Settings(llm_credit_limit=0))
    llm = SilentLLM()

    with caplog.at_level(logging.WARNING, logger="app.analysis.engine"):
        result = analyze(req, {"MES_MAIN": data / "mes"}, llm=llm)

    summary = result.summary
    # 부른 수는 실제 호출만, 건너뛴 묶음은 따로 센다
    assert summary["llmCalls"] == len(llm.prompts) == summary["llmFailedCalls"]
    assert summary["llmSkippedCalls"] == batches - 3
    assert summary["llmSkipReason"] == "LLM 호출이 모두 실패함"
    assert summary["llmUsed"] is False
    assert any(
        line.startswith(f"분석 실행 1 LLM 메모 추출 실패 {batches}건")
        and f"LLM 끊김 {batches - 3}건" in line
        and "응답 없음(TimeoutError) 3건" in line
        for line in (r.getMessage() for r in caplog.records)
    )


@pytest.mark.parametrize(
    "error, kind",
    [
        ("GMS 요청 실패 (HTTP 429): {...}", "HTTP 429"),
        ("GMS 응답이 없습니다: TimeoutError", "응답 없음(TimeoutError)"),
        ("GMS 접속하지 못했습니다: [Errno 111]", "접속 실패"),
        ("GMS가 JSON이 아닌 답을 보냈습니다: HTTP 500 이라는 글", "JSON 아님"),
        ("1 validation error for Answer", "답 형식 오류"),
        (None, "기타"),
    ],
)
def test_llm_error_kind_keeps_only_cause(error, kind):
    assert engine.llm_error_kind(error) == kind


def test_postgres_source_is_closed_before_llm_stage(monkeypatch):
    conn = FakeConn()
    reader = PostgresSourceReader("MES_MAIN", lambda: conn, schema="mes")
    seen = {}

    def after_snapshot(req, root, llm, credit_limit=0):
        seen["closed"] = conn.closed
        seen["files"] = sorted(p.name for p in (root / "mes").iterdir())
        return engine.AnalysisResult(summary={}, proposals=[])

    monkeypatch.setattr(engine, "_analyze", after_snapshot)
    req = request([("mes.work_order", {})], kind="POSTGRES", schema="mes")

    analyze(req, {"MES_MAIN": reader}, model_summary={})

    # 분석 단계는 임시 폴더만 읽으므로 원천 연결은 스냅샷 직후 닫는다
    assert seen == {"closed": True, "files": ["_schema.json", "work_order.csv"]}


def test_sources_sharing_a_schema_name_are_rejected(data):
    req = AnalysisStartRequest.model_validate(
        {
            "runId": 1,
            "runKind": "INITIAL",
            "targets": [
                {"sourceResourceId": 1, "dataSourceCode": c, "resourceKey": k}
                for c, k in (("ERP_MAIN", "mes.item"), ("MES_MAIN", "mes.work_order"))
            ],
            "sources": [
                {"dataSourceCode": c, "sourceKind": "CSV", "path": str(data / "mes")}
                for c in ("ERP_MAIN", "MES_MAIN")
            ],
        }
    )

    with pytest.raises(AnalysisFailure, match=r"같은 스키마 이름\(mes\)"):
        analyze(req, {"ERP_MAIN": data / "mes", "MES_MAIN": data / "mes"})


def test_stale_run_folders_are_removed_on_server_start(monkeypatch, tmp_path):
    from fastapi.testclient import TestClient

    import main
    from app.api.analysis_runs import get_runner

    (tmp_path / "ai-run-crashed" / "mes").mkdir(parents=True)
    (tmp_path / "ai-run-crashed" / "mes" / "employee.csv").write_text("x")
    (tmp_path / "other").mkdir()
    monkeypatch.setattr(engine.tempfile, "tempdir", str(tmp_path))
    try:
        with TestClient(main.app):
            assert not (tmp_path / "ai-run-crashed").exists()
    finally:
        get_runner.cache_clear()  # 종료 때 stop()한 실행기를 다른 테스트가 쓰지 않게 한다
    assert (tmp_path / "other").is_dir()
