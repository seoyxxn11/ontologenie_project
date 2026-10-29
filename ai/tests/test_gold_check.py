import json
import shutil
from pathlib import Path

from app.validators.gold_check import check_gold

GOLD_SAMPLE = Path(__file__).parents[1] / "data" / "gold-v1.0"


def _copy_sample(tmp_path):
    target = tmp_path / "gold"
    shutil.copytree(GOLD_SAMPLE, target)
    return target


def _edit(folder, name, change):
    path = folder / name
    data = json.loads(path.read_text(encoding="utf-8"))
    change(data)
    path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")


def test_released_gold_passes():
    assert check_gold(GOLD_SAMPLE) == []


def test_missing_file_is_reported(tmp_path):
    folder = _copy_sample(tmp_path)
    (folder / "id_links.json").unlink()

    assert check_gold(folder) == ["파일 없음: id_links.json"]


def test_wrong_entity_span_is_reported(tmp_path):
    folder = _copy_sample(tmp_path)
    _edit(
        folder,
        "note_annotations.json",
        lambda docs: docs[0]["entities"][0].update(
            start=docs[0]["entities"][0]["start"] + 1
        ),
    )

    assert any("위치" in problem for problem in check_gold(folder))


def test_entity_without_assertion_is_reported(tmp_path):
    folder = _copy_sample(tmp_path)
    _edit(
        folder,
        "note_annotations.json",
        lambda docs: docs[0]["entities"][0].pop("assertion"),
    )

    assert any("assertion" in problem for problem in check_gold(folder))


def test_reference_transform_that_does_not_reproduce_match_is_reported(tmp_path):
    folder = _copy_sample(tmp_path)
    _edit(
        folder,
        "mapping_rules.json",
        # R-SO: 도착 쪽 숫자 추출을 빼면 2610021과 OD-2610-021이 같아지지 않는다
        lambda rules: rules[0]["reference_transforms"].update(target_transforms=[]),
    )

    assert any("같아지지 않습니다" in problem for problem in check_gold(folder))


def test_old_value_sets_are_rejected(tmp_path):
    folder = _copy_sample(tmp_path)
    _edit(
        folder,
        "ontology.json",
        lambda o: (
            o["properties"][0].update(data_type="number"),
            o["link_types"][0].update(cardinality="N:1"),
        ),
    )

    problems = check_gold(folder)
    assert any("data_type" in p for p in problems) and any(
        "cardinality" in p for p in problems
    )


def test_link_type_pointing_to_unknown_object_type_is_reported(tmp_path):
    folder = _copy_sample(tmp_path)
    _edit(
        folder, "ontology.json", lambda o: o["link_types"][0].update(target_type="Nope")
    )

    assert any(
        "link_type purchase_order_vendor_code.target_type" in problem
        for problem in check_gold(folder)
    )


def test_ambiguous_link_without_candidates_is_reported(tmp_path):
    folder = _copy_sample(tmp_path)
    _edit(
        folder,
        "id_links.json",
        lambda links: next(x for x in links if x["status"] == "ambiguous").update(
            candidates=["P101"]
        ),
    )

    assert any("ambiguous" in problem for problem in check_gold(folder))


def test_manifest_count_mismatch_is_reported(tmp_path):
    folder = _copy_sample(tmp_path)
    _edit(folder, "manifest.json", lambda m: m["counts"].update(id_links=99))

    assert any("counts.id_links" in problem for problem in check_gold(folder))
