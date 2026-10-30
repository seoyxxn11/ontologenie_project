from fastapi.testclient import TestClient

from main import app


def test_health_returns_ok():
    response = TestClient(app).get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "ai-ontology"
    assert body["llm_provider"] == "mock"
