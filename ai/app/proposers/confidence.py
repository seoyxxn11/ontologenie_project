"""FN-AIP-05 신뢰도. 규칙은 docs/evaluation.md 2절.

- 신호는 묶음(구조·값 비교·완전성·의미·반복 일치)으로 나누고, 묶음 안에서는 가장 큰 점수 하나만 더한다.
- 보류(HELD)할 만한 치명적 문제가 있으면 최대 0.49(낮음).
- LLM 판단은 점수에 더하지 않는다(llm_judgement로 따로 표시). LLM이 '맞지 않다'고 하면 보류·상한만 적용한다.
- 관계 점수는 값 포함·도착 키·이름 근거를 더해 최대 0.8이다(이름 근거는 10-28 추가).
- 서버 매칭률(FN-CHK)은 넣지 않는다.
"""

from app.models.candidate import Confidence, ScoreItem

CAP = 0.49
STRUCTURE, VALUE, COMPLETE, NAME, MEANING, REPEAT = (
    "구조",
    "값 비교",
    "완전성",
    "이름",
    "의미(LLM)",
    "반복 일치(LLM)",
)


def build(items: list[tuple[str, str, float]], hold: str | None = None) -> Confidence:
    return Confidence(
        items=[
            ScoreItem(group=g, label=label, weight=round(w, 4))
            for g, label, w in items
            if w > 0
        ],
        cap=CAP if hold else None,
        cap_reason=hold,
    )


def object_type(
    declared_pk: bool, observed_key: bool, referenced_by: int
) -> list[tuple]:
    items = []
    if declared_pk:
        items.append((STRUCTURE, "기본키 선언", 0.25))
    elif observed_key:
        items.append((STRUCTURE, "값이 모두 다른 키 컬럼", 0.15))
    if referenced_by:
        items.append(("참조", f"다른 표 {referenced_by}개가 가리킴", 0.25))
    return items


def object_mapping(declared_pk: bool) -> list[tuple]:
    return [
        (
            STRUCTURE,
            "기본키로 행 구분" if declared_pk else "값으로 찾은 키로 행 구분",
            0.5 if declared_pk else 0.3,
        )
    ]


def property_(declared_type: bool, null_ratio: float) -> list[tuple]:
    return [
        (
            VALUE,
            "선언된 자료형" if declared_type else "값 모양으로 추정한 자료형",
            0.35 if declared_type else 0.25,
        ),
        (COMPLETE, f"값 채움 {1 - null_ratio:.0%}", 0.15 * (1 - null_ratio)),
    ]


def property_mapping() -> list[tuple]:
    return [(STRUCTURE, "원천 컬럼을 그대로 옮김(원천에서 확인)", 0.5)]


KEY_TEXT = {
    "primary": "도착이 기본키",
    "alternate": "도착이 다른 유일 키",
    "none": "도착이 키가 아님(종류 값)",
}


def link(
    distinct_coverage: float, row_coverage: float, target_key: str, name: bool = False
) -> list[tuple]:
    coverage = min(distinct_coverage, row_coverage)
    items = [
        (VALUE, f"값 포함 {coverage:.0%}", 0.5 * coverage),
        (
            STRUCTURE,
            KEY_TEXT[target_key],
            {"primary": 0.2, "alternate": 0.1}.get(target_key, 0.0),
        ),
    ]
    if name:
        items.append((NAME, "컬럼 이름이 대상 표·키와 이어짐", 0.1))
    return items


def band(score: float | None) -> str:
    if score is None:
        return "미산출"
    return "높음" if score >= 0.8 else "보통" if score >= 0.5 else "낮음"
