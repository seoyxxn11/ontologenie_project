"""FN-AIP-05 근거 재확인. 후보를 만든 값과 별개로 원천을 다시 읽어 근거를 확인한다.

- 위치(원천.표 또는 원천.표.컬럼)가 실제로 있어야 한다.
- values가 있으면 그 값이 모두 그 컬럼에 실제로 있어야 한다.
- 확인된 근거가 하나도 없는 후보는 내보내지 않는다(정해진 규칙). 일부만 확인되면 보류 권고(HELD).
"""

from pathlib import Path

from app.models.candidate import Candidate, Evidence
from app.sources.csv_reader import CsvSourceReader, UnknownTableError


class SourceLookup:
    def __init__(self, root: Path):
        self.root = Path(root)
        self._columns: dict[str, set[str] | None] = {}
        self._values: dict[str, set[str]] = {}

    def _reader(self, source: str) -> CsvSourceReader | None:
        folder = self.root / source
        return CsvSourceReader(source, folder) if folder.is_dir() else None

    def columns(self, source: str, table: str) -> set[str] | None:
        key = f"{source}.{table}"
        if key not in self._columns:
            reader = self._reader(source)
            try:
                schema = reader.read_schema(table) if reader else None
            except UnknownTableError:
                schema = None
            self._columns[key] = {c.name for c in schema.columns} if schema else None
        return self._columns[key]

    def values(self, source: str, table: str, column: str) -> set[str]:
        key = f"{source}.{table}.{column}"
        if key not in self._values:
            df = self._reader(source).read_rows(table)
            self._values[key] = set(df[column].dropna().astype(str))
        return self._values[key]

    def check(self, evidence: Evidence) -> bool:
        parts = evidence.locator.split(".")
        if len(parts) < 2 or parts[0] != evidence.source_id:
            return False
        source, table = parts[0], parts[1]
        columns = self.columns(source, table)
        if columns is None:
            return False
        if len(parts) == 2:
            return not evidence.values
        names = parts[2].split("+")
        if any(n not in columns for n in names):
            return False
        if not evidence.values:
            return True
        if len(names) != 1:
            return False
        return set(evidence.values) <= self.values(source, table, names[0])


def verify(
    root: Path, candidates: list[Candidate]
) -> tuple[list[Candidate], list[Candidate]]:
    """(내보낼 후보, 근거가 없어 뺀 후보)."""
    lookup = SourceLookup(root)
    kept, dropped = [], []
    for candidate in candidates:
        checked = [
            e.model_copy(update={"verified": lookup.check(e)})
            for e in candidate.evidence
        ]
        candidate = candidate.model_copy(update={"evidence": checked})
        ok = [e for e in checked if e.verified]
        if not ok:
            dropped.append(candidate)
            continue
        if len(ok) < len(checked) and candidate.review_status == "PENDING":
            candidate = candidate.model_copy(
                update={
                    "review_status": "HELD",
                    "hold_reason": "근거 일부를 원천에서 다시 찾지 못함",
                }
            )
        kept.append(candidate)
    return kept, dropped
