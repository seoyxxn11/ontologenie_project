from datetime import datetime
from decimal import Decimal, InvalidOperation
from enum import Enum

from pydantic import BaseModel, model_validator


class TransformOp(str, Enum):
    """값 변환 허용 목록. 정의 관리 FN-MAP-05(이주헌)의 목록을 따른다. AI 후보도 이 목록 안에서만 만든다."""

    STRIP_PREFIX = "STRIP_PREFIX"
    ADD_PREFIX = "ADD_PREFIX"
    PAD_ZEROS = "PAD_ZEROS"
    UPPER = "UPPER"
    TRIM = "TRIM"
    EXTRACT_NUMBER = "EXTRACT_NUMBER"
    TO_INTEGER = "TO_INTEGER"
    TO_DECIMAL = "TO_DECIMAL"
    TO_DATE = "TO_DATE"
    MAP_VALUE = "MAP_VALUE"


TEXT_ARG_OPS = {TransformOp.STRIP_PREFIX, TransformOp.ADD_PREFIX}
DATE_FORMATS = ["%Y-%m-%d", "%Y.%m.%d", "%Y/%m/%d", "%Y%m%d"]


class TransformError(ValueError):
    """변환 실패. 정합성 검사에서는 조건 위반으로 분류될 수 있다."""


class TransformStep(BaseModel):
    op: TransformOp
    arg: str | int | dict[str, str] | None = None

    @model_validator(mode="after")
    def _arg_matches_op(self) -> "TransformStep":
        if self.op in TEXT_ARG_OPS:
            if not (isinstance(self.arg, str) and self.arg):
                raise ValueError(f"{self.op.value}에는 문자열 인자가 필요합니다.")
        elif self.op == TransformOp.PAD_ZEROS:
            if not (isinstance(self.arg, int) and self.arg > 0):
                raise ValueError("PAD_ZEROS에는 1 이상의 자릿수가 필요합니다.")
        elif self.op == TransformOp.MAP_VALUE:
            if not (isinstance(self.arg, dict) and self.arg):
                raise ValueError("MAP_VALUE에는 값 대응표가 필요합니다.")
        elif self.arg is not None:
            raise ValueError(f"{self.op.value}에는 인자가 없습니다.")
        return self


def apply_step(step: TransformStep, value: str) -> str:
    if step.op == TransformOp.TRIM:
        return value.strip()
    if step.op == TransformOp.UPPER:
        return value.upper()
    if step.op == TransformOp.STRIP_PREFIX:
        # 접두어가 없으면 그대로 둔다. 이미 다른 형식으로 입력된 값을 망가뜨리지 않기 위함.
        return value.removeprefix(step.arg)
    if step.op == TransformOp.ADD_PREFIX:
        return step.arg + value
    if step.op == TransformOp.PAD_ZEROS:
        return value.zfill(step.arg) if value.isdigit() else value
    if step.op == TransformOp.EXTRACT_NUMBER:
        # 숫자와 소수점만 남긴다. 예: '600톤' → '600', 'OD-2608-012' → '2608012'
        number = "".join(ch for ch in value if ch.isdigit() or ch == ".")
        if not any(ch.isdigit() for ch in number):
            raise TransformError(f"숫자가 없습니다: {value!r}")
        return number
    if step.op == TransformOp.TO_INTEGER:
        try:
            return str(int(value.strip()))
        except ValueError as error:
            raise TransformError(f"정수가 아닙니다: {value!r}") from error
    if step.op == TransformOp.TO_DECIMAL:
        try:
            return str(Decimal(value.strip()))
        except InvalidOperation as error:
            raise TransformError(f"소수가 아닙니다: {value!r}") from error
    if step.op == TransformOp.TO_DATE:
        for fmt in DATE_FORMATS:
            try:
                return datetime.strptime(value.strip(), fmt).date().isoformat()
            except ValueError:
                continue
        raise TransformError(f"날짜 형식이 아닙니다: {value!r}")
    if step.op == TransformOp.MAP_VALUE:
        if value not in step.arg:
            raise TransformError(f"대응표에 없는 값입니다: {value!r}")
        return step.arg[value]
    raise ValueError(f"지원하지 않는 변환입니다: {step.op}")


def apply_steps(steps: list[TransformStep], value: str | None) -> str | None:
    """값이 없으면(None) 변환하지 않고 None을 돌려준다. 빈 값을 다른 값과 연결하지 않기 위함.
    한 단계라도 실패하면 TransformError를 낸다."""
    if value is None:
        return None
    for step in steps:
        value = apply_step(step, value)
    return value


_LABELS = {
    TransformOp.TRIM: "앞뒤 공백 제거",
    TransformOp.UPPER: "대문자로",
    TransformOp.EXTRACT_NUMBER: "숫자만 남기기",
    TransformOp.TO_INTEGER: "정수로",
    TransformOp.TO_DECIMAL: "소수로",
    TransformOp.TO_DATE: "날짜로",
}


def describe_steps(steps: list[TransformStep]) -> str:
    """사람이 읽는 설명. 예: 'W' 빼기 → 3자리로 0 채우기 → 'E' 붙이기"""
    parts = []
    for step in steps:
        if step.op in _LABELS:
            parts.append(_LABELS[step.op])
        elif step.op == TransformOp.STRIP_PREFIX:
            parts.append(f"앞의 '{step.arg}' 빼기")
        elif step.op == TransformOp.ADD_PREFIX:
            parts.append(f"앞에 '{step.arg}' 붙이기")
        elif step.op == TransformOp.PAD_ZEROS:
            parts.append(f"{step.arg}자리로 0 채우기")
        elif step.op == TransformOp.MAP_VALUE:
            parts.append(f"대응표로 바꾸기({len(step.arg)}개)")
    return " → ".join(parts) if parts else "그대로"
