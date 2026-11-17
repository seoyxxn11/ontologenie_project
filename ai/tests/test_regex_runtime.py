"""긴 실패 입력도 제한 시간 안에 끝나는지 별도 프로세스에서 확인한다."""

import subprocess
import sys
from pathlib import Path


def test_long_nonmatching_text_does_not_trigger_backtracking():
    script = """
from app.extractors.equipment import LINE, UNIT, parse
from app.extractors.notes import HOUR
from app.llm.client import _strip_fence

digits = '1' * 100_000 + '!'
for pattern in (LINE, UNIT):
    assert pattern.search(digits) is None
for text in (
    digits,
    '1-' + ' ' * 100_000 + '!',
    '1-' + '1' * 100_000 + '!',
    '1- ' * 50_000 + '!',
):
    mention = parse(text, set())
    assert (mention.line, mention.unit) == (None, None)
assert HOUR.search(' ' * 100_000 + '!') is None
assert HOUR.search('오후 ' + ' ' * 100_000 + '!') is None
unfinished = '```' + ' ' * 100_000
assert _strip_fence(unfinished) == unfinished
assert _strip_fence('```json\\n{}' + ' ' * 100_000 + '\\n```') == '{}'
"""
    subprocess.run(
        [sys.executable, "-c", script],
        cwd=Path(__file__).resolve().parents[1],
        timeout=10,
        check=True,
        capture_output=True,
        text=True,
    )
