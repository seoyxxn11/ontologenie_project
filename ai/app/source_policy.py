"""원천 데이터 정책의 AI 쪽 사본(2차 방어).

기준은 백엔드 `backend/src/main/resources/application.yml`의 `axcore.source-data-policy`(S15P31C106-391)다.
백엔드가 원천 계정 허용 목록·분석 접수에서 금지 데이터를 먼저 막는다. AI는 그래도 들어온 값을 가리고,
정답셋·평가에서 금지 표·컬럼을 빼는 데만 이 목록을 쓴다. 백엔드 정책을 바꾸면 이 파일도 같이 바꾼다
(tests/test_source_policy.py가 두 목록이 같은지 확인한다).

- 금지(forbidden): 수집·분석·정답 어디에도 쓰지 않는다. 표 전체 또는 컬럼 하나.
- 민감(restricted): 속성으로는 두되 RESTRICTED로 표시하고, 속성 근거·LLM 질문에 값 예를 보내지 않는다.
  관계의 연결 조건에는 쓰지 않는다(플랫폼이 민감 속성을 조건으로 쓰는 규칙 승인을 거절, 정답셋도 뺀다).
  그 값 안의 사람 이름 모양 값은 app.llm.privacy가 가린다.
"""

# change_log는 근태 키(사번|근무일)를 담는 동기화 기록이라 표 전체를 금지한다(S15P31C106-405, 결정 D3).
FORBIDDEN_TABLES = frozenset(
    {"erp.attendance", "erp.fixed_asset", "erp.change_log", "mes.change_log"}
)

FORBIDDEN_COLUMNS = frozenset(
    {
        "erp.employee.emp_name",
        "erp.employee.position",
        "erp.employee.hire_date",
        "erp.employee.employment_type",
        "erp.employee.status",
        "mes.worker.name",
    }
)

RESTRICTED_COLUMNS = frozenset(
    {
        "mes.maintenance_order.technician",
        "erp.vendor.contact_name",
        "mes.worker.skill_level",
    }
)


def _table(ref: str) -> str:
    return ".".join(ref.split(".")[:2])


def is_forbidden(ref: str) -> bool:
    """'원천.표' 또는 '원천.표.컬럼'이 금지 대상인지."""
    return _table(ref) in FORBIDDEN_TABLES or ref in FORBIDDEN_COLUMNS


def is_restricted(ref: str) -> bool:
    """'원천.표.컬럼'이 민감 컬럼인지."""
    return ref in RESTRICTED_COLUMNS
