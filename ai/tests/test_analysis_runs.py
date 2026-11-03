"""분석 시작 알림 수신(API 018 하단)과 105 전송. 백엔드와 원천은 가짜로 바꾼다."""

import json
import logging
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import ClassVar

import pytest
from fastapi.testclient import TestClient

from app.analysis.backend import BackendError, results_reporter
from app.analysis.engine import AnalysisResult
from app.analysis.runner import UNEXPECTED, AnalysisRunner
from app.api.analysis_runs import get_runner
from app.config import Settings, get_settings
from app.models.analysis import AnalysisStartRequest
from main import app
from tests.fake_postgres import FakeConn, failing_connect

TOKEN = "analysis-start-test-token"
PASSWORD = "ai-password-for-test"
PATH = "/internal/v1/analysis-runs"


def body(run_id=7, resource_key="mes.work_order", **source):
    return {
        "runId": run_id,
        "runKind": "INITIAL",
        "targets": [
            {
                "sourceResourceId": 11,
                "dataSourceCode": "MES_MAIN",
                "resourceKey": resource_key,
                "columns": ["wo_no"],
                "sourceMetadata": {"description": "작업지시"},
            }
        ],
        "sources": [
            {
                "dataSourceCode": "MES_MAIN",
                "sourceKind": "POSTGRES",
                "connection": {
                    "host": "source-mes",
                    "port": 5432,
                    "database": "mes",
                    "sslmode": "require",
                    "username": "platform_ai",
                    "password": PASSWORD,
                },
                "schema": "mes",
                **source,
            }
        ],
        "allowedTransforms": ["TRIM"],  # 아직 쓰지 않는 선택 필드는 버린다
    }


class Backend:
    """105를 흉내 낸다. 받은 본문을 쌓고, fail_on 상태면 실패한다."""

    def __init__(self, fail_on=None):
        self.reports, self.fail_on = [], fail_on

    def __call__(self, run_id, payload):
        self.reports.append((run_id, payload))
        if payload["status"] == self.fail_on:
            raise BackendError("HTTP 409")


@pytest.fixture
def token(monkeypatch):
    monkeypatch.setenv("AI_ONTOLOGY_INTERNAL_TOKEN", TOKEN)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def use(runner):
    app.dependency_overrides[get_runner] = lambda: runner
    return runner


@pytest.fixture(autouse=True)
def clear_overrides():
    yield
    app.dependency_overrides.clear()


def post(payload, token=TOKEN):
    headers = {} if token is None else {"X-Internal-Token": token}
    return TestClient(app).post(PATH, json=payload, headers=headers)


@pytest.mark.parametrize("sent", [None, "wrong-token"])
def test_rejects_missing_or_wrong_token_before_reading_body(token, sent):
    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn))

    response = post({"runId": "not-a-number"}, token=sent)

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHENTICATED"
    assert backend.reports == []


def test_rejects_everything_when_token_is_not_configured(monkeypatch):
    monkeypatch.delenv("AI_ONTOLOGY_INTERNAL_TOKEN", raising=False)
    get_settings.cache_clear()
    try:
        response = post(body(), token="")
        assert response.status_code == 401
    finally:
        get_settings.cache_clear()


def test_accepts_checks_source_and_reports_running_then_engine_result(token):
    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn))

    response = post(body())

    assert response.status_code == 202
    assert response.json() == {"runId": 7, "accepted": True}
    assert backend.reports[0] == (7, {"status": "RUNNING"})
    result = backend.reports[1][1]
    assert result["status"] == "SUCCEEDED"
    mapping = next(p for p in result["proposals"] if p["itemKind"] == "OBJECT_MAPPING")
    assert mapping["payload"]["sourceId"] == "MES_MAIN"
    assert mapping["payload"]["resourceKey"] == "mes.work_order"


def test_engine_gets_open_sources_and_success_is_reported(token):
    seen = {}

    def analyze(request, sources):
        seen["schema"] = sources["MES_MAIN"].schema
        seen["target"] = request.targets[0]
        return AnalysisResult({"proposalCount": 1}, [{"itemKind": "OBJECT_TYPE"}])

    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn, analyze=analyze))

    post(body())

    assert seen["schema"] == "mes"
    assert seen["target"].table == "work_order"
    assert seen["target"].columns == ["wo_no"]
    assert backend.reports[-1] == (
        7,
        {
            "status": "SUCCEEDED",
            "resultSummary": {"proposalCount": 1},
            "proposals": [{"itemKind": "OBJECT_TYPE"}],
        },
    )


def test_same_run_still_running_is_not_started_again(token):
    backend = Backend()
    runner = use(AnalysisRunner(backend, connector=lambda c: FakeConn))
    assert runner.claim(7)

    response = post(body())

    assert response.json() == {"runId": 7, "accepted": False}
    assert backend.reports == []


def test_finished_run_can_be_started_again(token):
    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn))

    post(body())
    second = post(body())

    assert second.json()["accepted"] is True
    assert len(backend.reports) == 4


def test_table_the_ai_account_cannot_read_fails_run(token):
    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn))

    post(body(resource_key="mes.employee"))

    assert backend.reports[-1][1] == {
        "status": "FAILED",
        "errorMessage": "AI 계정이 이 테이블을 읽을 수 없습니다: mes.employee",
    }


def test_source_auth_failure_reports_cause_without_connection_details(token, caplog):
    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: failing_connect()))

    with caplog.at_level(logging.INFO):
        post(body())

    assert backend.reports[-1][1] == {
        "status": "FAILED",
        "errorMessage": "원천 계정 인증에 실패했습니다 (MES_MAIN)",
    }
    assert PASSWORD not in caplog.text and "db.internal" not in caplog.text


def test_unexpected_engine_error_is_reported_without_details(token, caplog):
    def analyze(request, sources):
        raise RuntimeError(f"secret {PASSWORD}")

    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn, analyze=analyze))

    with caplog.at_level(logging.INFO):
        post(body())

    assert backend.reports[-1][1] == {"status": "FAILED", "errorMessage": UNEXPECTED}
    assert PASSWORD not in caplog.text


def test_running_report_rejected_stops_without_analysis(token):
    called = []
    backend = Backend(fail_on="RUNNING")
    runner = use(
        AnalysisRunner(
            backend,
            connector=lambda c: called.append(c) or FakeConn,
        )
    )

    post(body())

    assert backend.reports == [(7, {"status": "RUNNING"})]
    assert called == []
    assert runner.claim(7)  # 실행 표시는 풀린다


def test_csv_source_checks_folder_and_table_files(token, tmp_path):
    (tmp_path / "work_order.csv").write_text("wo_no\nWO-1\n", encoding="utf-8")
    csv = {"sourceKind": "CSV", "connection": {}, "schema": None, "path": str(tmp_path)}
    backend = Backend()
    use(AnalysisRunner(backend))

    post(body(**csv))
    assert backend.reports[-1][1]["status"] == "SUCCEEDED"

    post(body(resource_key="mes.lot", **csv))
    assert backend.reports[-1][1]["errorMessage"] == (
        "CSV 원천에서 이 테이블을 찾을 수 없습니다: mes.lot"
    )

    post(body(**{**csv, "path": str(tmp_path / "missing")}))
    assert backend.reports[-1][1]["errorMessage"] == (
        "CSV 원천 폴더를 읽을 수 없습니다 (MES_MAIN)"
    )


def test_invalid_body_is_400_without_echoing_values(token):
    payload = body()
    payload["targets"][0]["dataSourceCode"] = "ERP_MAIN"

    response = post(payload)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_FAILED"
    assert PASSWORD not in response.text


def test_request_repr_hides_connection():
    request = AnalysisStartRequest.model_validate(body())
    assert PASSWORD not in repr(request)
    assert request.sources[0].db_schema == "mes"


class _Recorder(BaseHTTPRequestHandler):
    received: ClassVar[list] = []
    status = 200

    def do_POST(self):
        length = int(self.headers["Content-Length"])
        self.received.append(
            (
                self.path,
                self.headers["X-Internal-Token"],
                json.loads(self.rfile.read(length)),
            )
        )
        self.send_response(self.status)
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *args):
        pass


@pytest.fixture
def backend_server():
    _Recorder.received, _Recorder.status = [], 200
    server = ThreadingHTTPServer(("127.0.0.1", 0), _Recorder)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield server
    server.shutdown()


def test_reporter_posts_to_105_with_token(backend_server):
    port = backend_server.server_address[1]
    report = results_reporter(
        Settings(backend_url=f"http://127.0.0.1:{port}/", internal_token=TOKEN)
    )

    report(7, {"status": "FAILED", "errorMessage": "분석 중 오류가 났습니다"})

    assert _Recorder.received == [
        (
            "/api/v1/internal/analyses/7/results",
            TOKEN,
            {"status": "FAILED", "errorMessage": "분석 중 오류가 났습니다"},
        )
    ]


def test_reporter_turns_http_and_connection_errors_into_backend_error(backend_server):
    port = backend_server.server_address[1]
    _Recorder.status = 409
    report = results_reporter(Settings(backend_url=f"http://127.0.0.1:{port}"))
    with pytest.raises(BackendError, match="HTTP 409"):
        report(7, {"status": "RUNNING"})

    closed = results_reporter(Settings(backend_url="http://127.0.0.1:1"))
    with pytest.raises(BackendError):
        closed(7, {"status": "RUNNING"})
