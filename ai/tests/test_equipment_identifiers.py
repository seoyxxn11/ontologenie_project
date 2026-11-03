import pytest

from app.dataset.build import generate
from app.extractors.equipment import load_equipment, resolve
from app.extractors.identifiers import find, load_dictionary

ROWS = [
    {"code": "P101", "name": "1라인 1호 프레스", "line": "1라인"},
    {"code": "P103", "name": "1라인 3호 프레스", "line": "1라인"},
    {"code": "P201", "name": "2라인 1호 프레스", "line": "2라인"},
    {"code": "B101", "name": "1라인 블랭킹기", "line": "1라인"},
]


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
