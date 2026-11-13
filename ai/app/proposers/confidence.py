"""FN-AIP-05 신뢰도. 규칙과 근거 수치는 docs/evaluation.md 2절.

신뢰도 = 같은 근거로 나온 제안이 평가 데이터에서 실제로 맞은 비율. (맞은 수 + 1) / (전체 + 2)를 0.05 단위로
맞춰 개수가 적은 근거가 100%로 튀지 않게 한다. 보류는 꼭 확인할 제안이라 0.6을 넘기지 않는다.
LLM 판단은 점수에 더하지 않고 상한에만 쓴다.
"""

from app.models.candidate import Confidence, ScoreItem

# LLM이 업무상 맞지 않다고 본 관계의 상한(평가 수치 없어 보수적으로 둠)
CAP = 0.3
BASIS = "근거"
STRUCTURE, VALUE, COMPLETE = "구조", "값 비교", "완전성"

# 보류 사유 앞부분 → (상한, 맞은 수, 전체). 위에서부터 먼저 맞는 것
HOLD_CAPS: list[tuple[str, float, int, int]] = [
    ("LLM이 업무상 맞지 않다고 봄", CAP, 0, 0),
    ("접두어 '", 0.2, 1, 8),
    ("숫자뿐인 값에", 0.3, 4, 14),
    ("원래 값이 하나도 같지 않고 숫자만", 0.3, 1, 3),
    ("글자가 다른 값까지 숫자만 남겨", 0.3, 0, 1),
    ("출발 값에 글자가 없고 숫자만 같음", 0.3, 0, 0),
    ("출발 값의 글자를 떼야", 0.3, 0, 0),
    ("다른 시스템의 두 컬럼을 변환해", 0.35, 0, 1),
    ("값 종류 ", 0.4, 0, 1),
    ("코드 값 ", 0.3, 0, 2),
    ("연도·순번 문서 번호가 숫자만", 0.3, 0, 3),
    ("경유 관계라 연결 규칙", 0.4, 0, 0),
    ("도착이 1, 2, 3", 0.45, 5, 11),
    ("값 종류가", 0.5, 4, 6),
    ("서로 다른 값 기준", 0.5, 2, 4),
    ("행 기준으로만", 0.5, 1, 1),
    ("대소문자·구분자만 달라", 0.6, 5, 7),
    ("끝의 차수 글자를 떼면", 0.6, 1, 2),
    ("숫자 묶음만 같아", 0.6, 1, 1),
    ("구분자만 달라 숫자만", 0.6, 0, 0),
    ("숫자만 남기고 앞자리", 0.6, 4, 5),
    ("다른 시스템의 번호를 숫자로만", 0.6, 3, 3),
    ("시스템 간 연결이라", 0.6, 0, 0),
    ("수량·개수 컬럼이라", 0.4, 0, 0),
    ("같은 표의 상위 코드인데", 0.5, 0, 0),  # 값이 적은 위계
    ("식별 키 없음", 0.6, 0, 0),  # 객체 유형
    ("순번 숫자 컬럼", 0.45, 0, 0),  # 복합키 둘째 컬럼
    # 근거 재확인(evidence.verify): 원천에서 다시 못 찾은 근거가 있으면 다른 근거가 강해도 낮게 둔다
    ("근거 일부를 원천에서 다시 찾지 못함", 0.3, 0, 0),
    ("후보", 0.3, 0, 0),  # 문서 연결
    ("대상을 정한 언급이 없음", 0.3, 0, 0),
]
HOLD_DEFAULT = 0.45
# 보류 권고인데 LLM이 맞다고 본 연결 규칙은 훨씬 잘 맞아 상한을 보류 최댓값까지 올린다
LLM_AGREE_HELD = (0.6, 19, 22)


def hold_cap(reason: str) -> tuple[float, str]:
    """(상한, 화면에 보일 설명)."""
    for prefix, cap, ok, total in HOLD_CAPS:
        if reason.startswith(prefix) or (len(prefix) > 6 and prefix in reason):
            stat = (
                f" — 이런 보류는 평가 데이터에서 {total}개 중 {ok}개가 맞음"
                if total
                else ""
            )
            return cap, f"{reason}{stat}"
    return HOLD_DEFAULT, reason


def with_hold(confidence: Confidence | None, hold: str) -> Confidence | None:
    """나중에 보류로 바뀐 후보(근거 재확인 등)에 그 사유의 상한을 더한다. 이미 더 낮은 상한이 있으면 그대로 둔다."""
    if confidence is None:
        return None
    cap, cap_reason = hold_cap(hold)
    if confidence.cap is not None and confidence.cap <= cap:
        return confidence
    return confidence.model_copy(update={"cap": cap, "cap_reason": cap_reason})


def build(items: list[tuple[str, str, float]], hold: str | None = None) -> Confidence:
    cap, cap_reason = hold_cap(hold) if hold else (None, None)
    return Confidence(
        items=[
            ScoreItem(group=g, label=label, weight=round(w, 4))
            for g, label, w in items
            if w > 0
        ],
        cap=cap,
        cap_reason=cap_reason,
    )


def object_type(
    declared_pk: bool, observed_key: bool, referenced_by: int
) -> list[tuple]:
    """원천 표 한 행 = 객체 하나. 평가 데이터의 표는 모두 정답 객체 유형이었다."""
    keyed = declared_pk or observed_key
    key = (
        "기본키 선언"
        if declared_pk
        else "값이 모두 다른 키 컬럼"
        if observed_key
        else "행을 구분할 키 없음"
    )
    refs = f", 다른 표 {referenced_by}개가 가리킴" if referenced_by else ""
    return [
        (
            BASIS,
            f"원천 표의 한 행을 객체 하나로 옮김({key}{refs}) — 평가 데이터의 표는 모두 맞음",
            0.95 if keyed else 0.85,
        )
    ]


def object_mapping(declared_pk: bool) -> list[tuple]:
    return [
        (
            STRUCTURE,
            "기본키로 행 구분" if declared_pk else "값으로 찾은 키로 행 구분",
            0.95 if declared_pk else 0.85,
        )
    ]


def property_(declared_type: bool, null_ratio: float) -> list[tuple]:
    return [
        (
            VALUE,
            "선언된 자료형" if declared_type else "값 모양으로 추정한 자료형",
            0.8 if declared_type else 0.65,
        ),
        (COMPLETE, f"값 채움 {1 - null_ratio:.0%}", 0.15 * (1 - null_ratio)),
    ]


def property_mapping() -> list[tuple]:
    return [(STRUCTURE, "원천 컬럼을 그대로 옮김(원천에서 확인)", 0.95)]


# 대기로 낸 연결 규칙: (도착 키, 이름 근거) → (점수, 맞은 수, 전체)
PENDING = {
    ("primary", True): (0.95, 82, 83),
    ("primary", False): (0.85, 39, 44),
    ("alternate", None): (0.75, 5, 6),
    ("none", None): (0.65, 1, 1),
}
CROSS_SAME_VALUE = (0.7, 6, 8)
KEY_TEXT = {
    "primary": "도착이 기본키",
    "alternate": "도착이 다른 유일 키",
    "none": "도착이 키가 아님(종류 값)",
}


def link(
    distinct_coverage: float,
    row_coverage: float,
    target_key: str,
    name: bool = False,
    cross_same_value: bool = False,
) -> list[tuple]:
    coverage = min(distinct_coverage, row_coverage)
    key = (target_key, name if target_key == "primary" else None)
    score, ok, total = PENDING[key]
    text = KEY_TEXT[target_key] + (", 컬럼 이름도 대상과 이어짐" if name else "")
    items = [
        (
            BASIS,
            f"{text}, 값 포함 {coverage:.0%} — 평가 데이터에서 {total}개 중 {ok}개가 맞음",
            score,
        )
    ]
    if cross_same_value:
        # 다른 시스템인데 값이 글자 그대로 같으면 같은 모양의 다른 코드일 수 있다
        score, ok, total = CROSS_SAME_VALUE
        items = [
            (
                BASIS,
                (
                    f"다른 시스템인데 값이 글자 그대로 같음({text}, 값 포함 {coverage:.0%}) — "
                    f"평가 데이터에서 {total}개 중 {ok}개가 맞음"
                ),
                score,
            )
        ]
    return items


def document_link(exact_key: bool) -> list[tuple]:
    """메모 속 언급 → 원천 객체. 원천 키가 그대로 적힌 언급은 평가에서 잘못 찾은 것이 없었다."""
    if exact_key:
        return [
            (
                BASIS,
                "글에 원천 키가 그대로 적혀 있음 — 평가에서 잘못 찾은 것 0건(20개 중 20개)",
                0.95,
            )
        ]
    return [(BASIS, "글의 설비 이름·별명을 설비 목록과 맞춤", 0.8)]


def band(score: float | None) -> str:
    if score is None:
        return "미산출"
    return "높음" if score >= 0.8 else "보통" if score >= 0.5 else "낮음"
