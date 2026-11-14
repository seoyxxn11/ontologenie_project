"""객체 유형의 조회 그룹 기본값과 속성 민감도 분류 기준(S15P31C106-392).

플랫폼은 그룹이 없는 유형을 아무 역할에도 보여 주지 않는다(V21). 그래서 AI가 원천 업무 영역으로 그룹 기본값을 제안하고,
검토자가 화면에서 바꾸거나 비운다. 업무 영역을 정하지 못한 표는 비워 두어 승인하면 비공개로 들어간다.

그룹(백엔드 V13): PRODUCTION(생산·설비), SALES_SUPPLY(영업·구매·재고), FINANCE(재무), HR(인사).
FINANCE·HR은 지금 어느 역할에도 열려 있지 않으므로 AI가 고르지 않는다.
"""

import re

PRODUCTION = "PRODUCTION"
SALES_SUPPLY = "SALES_SUPPLY"

GROUP_TEXT = {PRODUCTION: "생산·설비", SALES_SUPPLY: "영업·구매·재고"}

# 표 이름 낱말(밑줄로 나눔). 2026-11-13 운영 지정(25개 표)과 같게 나온다. line은 주문 줄(sales_order_line)과 생산 라인에
# 함께 쓰여 넣지 않는다(MES 표는 원천으로 생산·설비가 된다).
_SALES_SUPPLY_WORDS = frozenset(
    {
        "sales",
        "purchase",
        "vendor",
        "supplier",
        "customer",
        "shipment",
        "receipt",
        "stock",
        "inventory",
        "item",
        "material",
        "warehouse",
        "delivery",
        "invoice",
    }
)
_PRODUCTION_WORDS = frozenset(
    {
        "work",
        "production",
        "equipment",
        "machine",
        "die",
        "mold",
        "downtime",
        "defect",
        "inspection",
        "routing",
        "operation",
        "process",
        "sensor",
        "worker",
        "handover",
        "maintenance",
        "department",
        "employee",
        "shift",
    }
)


def access_group(resource_key: str) -> tuple[str | None, str]:
    """'원천.표'의 조회 그룹 기본값과 그 이유. 정하지 못하면 (None, 이유)."""
    schema, _, table = resource_key.rpartition(".")
    words = set(table.lower().split("_"))
    sales = sorted(words & _SALES_SUPPLY_WORDS)
    production = sorted(words & _PRODUCTION_WORDS)
    if sales and production:
        return None, (
            f"표 이름이 영업·구매·재고({', '.join(sales)})와 생산·설비({', '.join(production)})에 함께 걸려 "
            "업무 영역을 정하지 못함. 비워 두면 승인 뒤 비공개"
        )
    if sales:
        return SALES_SUPPLY, f"표 이름({', '.join(sales)})이 영업·구매·재고 업무"
    if production:
        return PRODUCTION, f"표 이름({', '.join(production)})이 생산·설비 업무"
    if schema.lower().endswith("mes"):
        return PRODUCTION, "MES 원천이라 생산·설비 업무"
    return None, "업무 영역을 정하지 못함. 비워 두면 승인 뒤 비공개"


# 사람 한 명을 행으로 갖는 표. 이 표에서만 인사·평가 낱말을 민감으로 본다(제품 등급·검사 점수는 사람 평가가 아니다).
_PERSON_TABLE_WORDS = frozenset(
    {"employee", "worker", "staff", "user", "member", "person", "technician"}
)
# 사람을 알아볼 수 있는 값(연락처·주소 등). 이름은 is_personal_column이 따로 본다.
_IDENTIFYING_WORDS = frozenset(
    {"phone", "mobile", "email", "address", "birth", "birthday", "resident", "ssn"}
)
_HR_WORDS = frozenset(
    {
        "position",
        "rank",
        "hire",
        "hired",
        "employment",
        "salary",
        "wage",
        "gender",
        "marital",
        "retire",
        "retired",
        "resign",
        "resigned",
        "nationality",
    }
)
_EVALUATION_WORDS = frozenset(
    {"skill", "rating", "score", "evaluation", "performance", "appraisal", "review"}
)
_FREE_TEXT_WORDS = frozenset(
    {
        "note",
        "notes",
        "memo",
        "comment",
        "comments",
        "remark",
        "remarks",
        "content",
        "description",
        "text",
        "body",
    }
)
_WORD = re.compile(r"[a-z0-9]+")


def _words(text: str) -> set[str]:
    return set(_WORD.findall(text.lower()))


# 기계 주소(ip_address·mac_address)는 사람을 알아볼 수 있는 값이 아니다
_MACHINE_WORDS = frozenset({"ip", "mac"})
# 코드·번호·ID 칸은 다른 표를 가리키는 연결 키일 수 있다(position_code → 직급 표). 민감으로 두면 연결 규칙 승인이 막힌다
_CODE_SUFFIXES = ("_id", "_code", "_cd", "_no", "_key")


def is_code_like(column: str) -> bool:
    name = column.lower()
    return name in {"id", "code", "no", "key"} or name.endswith(_CODE_SUFFIXES)


def is_person_table(resource_key: str) -> bool:
    return bool(_words(resource_key.rpartition(".")[2]) & _PERSON_TABLE_WORDS)


def sensitivity_category(resource_key: str, column: str) -> str | None:
    """사람 표의 컬럼이 민감 분류 기준(사람을 알아볼 수 있는 값, 인사 속성, 평가)에 걸리면 그 기준. 아니면 None.

    이름 컬럼과 값이 사람 이름 모양인 컬럼은 is_personal_column이 따로 본다. 여기서는 낱말로만 본다.
    """
    if not is_person_table(resource_key):
        return None
    words = _words(column)
    # 연락처·주소는 번호 칸(phone_no)이어도 사람을 알아볼 수 있는 값이다. 그 밖의 코드·번호 칸은 연결 키일 수 있어 보지 않는다
    if words & _IDENTIFYING_WORDS and not words & _MACHINE_WORDS:
        return "사람을 알아볼 수 있는 값"
    if is_code_like(column):
        return None
    if words & _HR_WORDS:
        return "인사 속성"
    if words & _EVALUATION_WORDS:
        return "평가"
    return None


def is_free_text(column: str, comment: str | None) -> bool:
    """자유 글 칸(메모·비고·내용). 사람 이름이 섞일 수 있으나 업무 내용이라 자동으로 민감으로 두지 않고 검토를 권한다.

    인계 메모 같은 칸을 민감으로 두면 관리자·Agent가 내용을 볼 수 없다.
    """
    return bool(_words(column) & _FREE_TEXT_WORDS) or bool(
        comment and re.search(r"(메모|비고)", comment)
    )
