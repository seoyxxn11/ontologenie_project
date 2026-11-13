import json
from pathlib import Path

from app.dataset.build import generate
from app.dataset.gold import allowed_relations
from app.dataset.stress import build, evaluate


def test_stress_set_keeps_gold_apart_and_adds_decoys(tmp_path):
    # 평가용 변형 데이터(C)는 확정 데이터셋과 따로 관리한다
    generate("t2", root=tmp_path)
    result = build("t2", root=tmp_path)
    sample, gold = Path(result["sample"]), Path(result["gold"])
    expected = json.loads((gold / "expected.json").read_text(encoding="utf-8"))
    assert len(expected["changes"]) == 6
    header = (
        (sample / "mes" / "downtime.csv").read_text(encoding="utf-8").splitlines()[0]
    )
    assert header.endswith("shift_seq")
    assert not (sample / "expected.json").exists()
    report = evaluate(sample, expected)
    assert report["must_total"] == len(allowed_relations())
