"""FN-AIP-03 설비 ID 연결. 글 속 설비 언급을 설비 마스터의 실제 ID에 잇는다 (코드, LLM 미사용).

설비 이름(예: '1라인 3호 프레스')에서 라인 번호·호기 번호·종류 단어를 읽어 두고,
언급(예: '3호기', '1-3호', '1라인 3호', 'p103')에서 읽은 값과 맞는 설비를 찾는다.
- 하나면 연결(resolved), 여럿이면 모두 후보로(ambiguous), 없으면 미연결(unmatched).
- 작성자 배치 라인 같은 단서는 이유에만 적고 후보를 줄이지 않는다(명세: 후보 여럿이면 모두 제시).
"""

import re
from dataclasses import dataclass, field

from app.models.extraction import Resolution, ResolutionStatus

LINE = re.compile(r"(\d+)\s*라인")
UNIT = re.compile(r"(\d+)\s*호")
LINE_UNIT = re.compile(r"(\d+)\s*-\s*(\d+)\s*호")


@dataclass
class Equipment:
    code: str
    name: str
    line: int | None
    unit: int | None
    words: set[str] = field(
        default_factory=set
    )  # 이름에 든 종류 단어. 예: 프레스, 블랭킹기


@dataclass
class Mention:
    line: int | None = None
    unit: int | None = None
    words: set[str] = field(default_factory=set)


def _words(name: str) -> set[str]:
    rest = LINE_UNIT.sub(" ", name)
    rest = LINE.sub(" ", rest)
    rest = UNIT.sub(" ", rest)
    return {w for w in rest.split() if re.fullmatch(r"[가-힣]{2,}", w)}


def load_equipment(rows: list[dict]) -> list[Equipment]:
    out = []
    for r in rows:
        name = r.get("name") or ""
        line = LINE.search(name) or LINE.search(r.get("line") or "")
        unit = UNIT.search(name)
        out.append(
            Equipment(
                code=r["code"],
                name=name,
                line=int(line.group(1)) if line else None,
                unit=int(unit.group(1)) if unit else None,
                words=_words(name),
            )
        )
    return out


def parse(text: str, vocabulary: set[str]) -> Mention:
    m = Mention()
    both = LINE_UNIT.search(text)
    if both:
        m.line, m.unit = int(both.group(1)), int(both.group(2))
    else:
        line, unit = LINE.search(text), UNIT.search(text)
        m.line = int(line.group(1)) if line else None
        m.unit = int(unit.group(1)) if unit else None
    m.words = {w for w in vocabulary if w in text}
    return m


def resolve(text: str, equipment: list[Equipment]) -> Resolution:
    by_code = {e.code.upper(): e for e in equipment}
    if text.strip().upper() in by_code:
        return Resolution(
            status=ResolutionStatus.RESOLVED,
            ref=f"mes.equipment:{by_code[text.strip().upper()].code}",
        )
    vocabulary = {w for e in equipment for w in e.words}
    m = parse(text, vocabulary)
    if m.line is None and m.unit is None and not m.words:
        return Resolution(status=ResolutionStatus.UNMATCHED)
    hits = [
        e
        for e in equipment
        if (m.line is None or e.line == m.line)
        and (m.unit is None or e.unit == m.unit)
        and (not m.words or m.words <= e.words)
    ]
    refs = [f"mes.equipment:{e.code}" for e in hits]
    if len(refs) == 1:
        return Resolution(status=ResolutionStatus.RESOLVED, ref=refs[0])
    if len(refs) > 1:
        return Resolution(status=ResolutionStatus.AMBIGUOUS, candidates=refs)
    return Resolution(status=ResolutionStatus.UNMATCHED)


def looks_like_equipment(text: str, equipment: list[Equipment]) -> bool:
    """설비 코드이거나 라인·호기·설비 종류 단어가 있으면 설비 언급으로 본다.

    '유압 펌프'·'펀치'처럼 설비에 달린 부품 이름은 여기서 걸러진다. '1라인 2호기'처럼
    목록에 없는 설비도 설비 언급으로는 남기고 연결만 하지 않는다(미연결).
    """
    if text.strip().upper() in {e.code.upper() for e in equipment}:
        return True
    m = parse(text, {w for e in equipment for w in e.words})
    return m.line is not None or m.unit is not None or bool(m.words)
