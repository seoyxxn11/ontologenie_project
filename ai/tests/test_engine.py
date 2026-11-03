from pathlib import Path

import pytest

from app.analysis import engine
from app.analysis.engine import AnalysisFailure, analyze
from app.dataset.build import generate
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


def test_csv_source_gives_candidates_only_for_chosen_tables(data):
    req = request([("mes.work_order", {}), ("mes.production_result", {})])

    result = analyze(req, {"MES_MAIN": data / "mes"})

    objects = {p["payload"]["code"] for p in kinds(result, "OBJECT_TYPE")}
    assert objects == {"WORK_ORDER", "PRODUCTION_RESULT"}
    assert kinds(result, "LINK_RULE")  # 작업지시 ↔ 공정 실적
    assert result.summary["proposals"] == len(result.proposals)
    assert result.summary["analyzedTables"] == [
        "mes.production_result",
        "mes.work_order",
    ]
    assert result.summary["llmSkipReason"] == "LLM 설정 없음"


def test_source_id_is_data_source_code_and_resource_key_keeps_schema(data):
    result = analyze(request([("mes.work_order", {})]), {"MES_MAIN": data / "mes"})

    mapping = kinds(result, "OBJECT_MAPPING")[0]["payload"]
    assert mapping["sourceId"] == "MES_MAIN"
    assert mapping["resourceKey"] == "mes.work_order"
    assert "'sourceId': 'mes'" not in str(result.proposals)


def test_only_chosen_columns_and_registered_metadata_are_used(data):
    target = {
        "columns": ["product_code"],
        "sourceMetadata": {"columns": {"product_code": {"synonyms": ["품번"]}}},
    }
    result = analyze(request([("mes.work_order", target)]), {"MES_MAIN": data / "mes"})

    props = {p["payload"]["code"]: p["payload"] for p in kinds(result, "PROPERTY")}
    assert set(props) == {"wo_no", "product_code"}  # 기본키는 행 구별용으로 함께 읽는다
    assert "동의어: 품번" in props["product_code"]["description"]


def test_open_postgres_reader_is_snapshotted_under_schema_name():
    reader = PostgresSourceReader("MES_MAIN", FakeConn, schema="mes")
    req = request([("mes.work_order", {})], kind="POSTGRES")

    result = analyze(req, {"MES_MAIN": reader})

    mapping = kinds(result, "OBJECT_MAPPING")[0]["payload"]
    assert mapping["resourceKey"] == "mes.work_order"
    assert mapping["sourceId"] == "MES_MAIN"


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
