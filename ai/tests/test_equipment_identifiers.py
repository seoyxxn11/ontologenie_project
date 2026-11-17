import pytest

from app.dataset.build import generate
from app.extractors.equipment import load_equipment, parse, resolve
from app.extractors.identifiers import find, load_dictionary

ROWS = [
    {"code": "P101", "name": "1라인 1호 프레스", "line": "1라인"},
    {"code": "P103", "name": "1라인 3호 프레스", "line": "1라인"},
    {"code": "P201", "name": "2라인 1호 프레스", "line": "2라인"},
    {"code": "B101", "name": "1라인 블랭킹기", "line": "1라인"},
]


@pytest.mark.parametrize(
    ("text", "line", "unit"),
    [
        ("3호기", None, 3),
        ("프레스 1 - 3 호", 1, 3),
        ("1-2-3호", 2, 3),
        ("1 - x 2 - 4호", 2, 4),
        ("9라인 8호 뒤 2-3호", 2, 3),
        ("１２\u2003-\n０３\t호기", 12, 3),
        ("1-\u200b3호", None, 3),
        ("1-2호 3-4호", 1, 2),
        ("설비 001\t라인 03\n호기", 1, 3),
        ("１라인 ３호기", 1, 3),
        ("1" + " " * 2000 + "라인 3" + " " * 2000 + "호", 1, 3),
    ],
)
def test_equipment_numbers_keep_spacing_and_unicode_support(text, line, unit):
    mention = parse(text, set())
    assert (mention.line, mention.unit) == (line, unit)


def test_equipment_words_remove_every_combined_line_unit_mention():
    equipment = load_equipment(
        [{"code": "TEST", "name": "프레스1-3호절곡기 2-4호용접기"}]
    )
    assert equipment[0].words == {"프레스", "절곡기", "용접기"}


def test_equipment_linking_keeps_every_candidate():
    equipment = load_equipment(ROWS)
    assert resolve("p103", equipment).ref == "mes.equipment:P103"
    assert resolve("3호기", equipment).ref == "mes.equipment:P103"
    assert resolve("1-3호", equipment).ref == "mes.equipment:P103"
    assert resolve("1라인 블랭킹기", equipment).ref == "mes.equipment:B101"
    both = resolve("1호기", equipment)
    assert both.status == "ambiguous" and len(both.candidates) == 2
    assert resolve("1라인 2호기", equipment).status == "unmatched"


@pytest.fixture(scope="module")
def data(tmp_path_factory):
    root = tmp_path_factory.mktemp("data")
    generate("t4", root=root)
    return root / "data-t4"


def test_identifiers_point_to_row_keys(data):
    dictionary = load_dictionary(data)
    hits = {
        h.text: h
        for h in find("JB2610-0141 성형 중단. LT261024-1 확인. p103 점검", dictionary)
    }
    assert hits["JB2610-0141"].ref == "mes.work_order:JB2610-0141"
    assert hits["LT261024-1"].ref.startswith(
        "erp.shipment:DL"
    )  # 로트 값이 아니라 출하 행의 키
    assert hits["p103"].type.value == "EQUIPMENT"


def test_same_key_value_in_several_rows_keeps_every_row(tmp_path):
    # 같은 로트를 출하 두 건에 나눠 실으면 예전에는 마지막 행만 남아 그 출하에만 이어졌다
    from tests.test_links_general import write_table

    write_table(
        tmp_path,
        "erp",
        "shipment",
        ["ship_no"],
        {
            "ship_no": ("varchar(10)", ["DL-1", "DL-2", "DL-3"]),
            "lot_no": ("varchar(12)", ["LT2610-01", "LT2610-01", "LT2610-02"]),
        },
    )

    dictionary = load_dictionary(tmp_path)
    hits = {h.text: h for h in find("LT2610-01, LT2610-02 확인", dictionary)}

    assert hits["LT2610-01"].candidates == ["erp.shipment:DL-1", "erp.shipment:DL-2"]
    assert hits["LT2610-02"].candidates == ["erp.shipment:DL-3"]
    assert hits["LT2610-02"].ref == "erp.shipment:DL-3"
