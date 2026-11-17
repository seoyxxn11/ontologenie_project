"""글 속 설비 언급(예: '3호기', 'p103')을 라인·호기·종류 단어로 설비 마스터 ID에 잇는다 (LLM 미사용).

하나면 resolved, 여럿이면 모두 후보(ambiguous), 없으면 unmatched. 작성자 라인 같은 단서는 이유에만 적는다.
"""

import re
from collections.abc import Iterator
from dataclasses import dataclass, field

from app.models.extraction import Resolution, ResolutionStatus

# 숫자열 중간에서 다시 검색하지 않고 숫자·공백을 되돌리지 않는다(Python 3.11+).
LINE = re.compile(r"(?<!\d)(\d++)\s*+라인")
UNIT = re.compile(r"(?<!\d)(\d++)\s*+호")
LINE_UNIT_PREFIX = re.compile(r"(?<!\d)(\d++)\s*+-")


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


def _line_units(text: str) -> Iterator[tuple[re.Match[str], re.Match[str]]]:
    for line in LINE_UNIT_PREFIX.finditer(text):
        unit_start = line.end()
        while unit_start < len(text) and text[unit_start].isspace():
            unit_start += 1
        unit = UNIT.match(text, unit_start)
        if unit:
            yield line, unit


def _words(name: str) -> set[str]:
    parts = []
    start = 0
    for line, unit in _line_units(name):
        parts.append(name[start : line.start()])
        start = unit.end()
    parts.append(name[start:])
    rest = " ".join(parts)
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
    both = next(_line_units(text), None)
    if both:
        line, unit = both
        m.line, m.unit = int(line.group(1)), int(unit.group(1))
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

    부품 이름('유압 펌프')은 걸러지고, 목록에 없는 설비('1라인 2호기')는 언급으로 남기되 연결하지 않는다.
    """
    if text.strip().upper() in {e.code.upper() for e in equipment}:
        return True
    m = parse(text, {w for e in equipment for w in e.words})
    return m.line is not None or m.unit is not None or bool(m.words)
