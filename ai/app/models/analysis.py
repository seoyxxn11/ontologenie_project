"""분석 시작 알림(API 018 하단, POST /internal/v1/analysis-runs)의 요청 모양.

JSON은 camelCase, 코드는 snake_case로 쓴다. 명세의 선택 필드(allowedTransforms 등)는 아직 쓰지 않아 받아도 버린다.
sources[].connection에는 원천 AI 계정 비밀번호가 있어 repr에서 뺀다. 저장하지 않고 분석이 끝나면 버린다.
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class _Wire(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, extra="ignore"
    )


class AnalysisTarget(_Wire):
    source_resource_id: int
    data_source_code: str
    resource_key: str  # 원천.테이블 (예: mes.work_order)
    columns: list[str] | None = None  # 없으면 전체 컬럼
    source_metadata: dict = Field(default_factory=dict)

    @property
    def table(self) -> str:
        return self.resource_key.split(".", 1)[-1]


class AnalysisSource(_Wire):
    data_source_code: str
    source_kind: Literal["POSTGRES", "CSV"]
    connection: dict = Field(default_factory=dict, repr=False)
    db_schema: str | None = Field(default=None, alias="schema")  # 없으면 public
    path: str | None = None  # CSV 폴더


class AnalysisStartRequest(_Wire):
    run_id: int
    run_kind: Literal["INITIAL", "REANALYSIS"]
    targets: list[AnalysisTarget] = Field(min_length=1)
    sources: list[AnalysisSource] = Field(min_length=1)

    @model_validator(mode="after")
    def _targets_have_sources(self) -> "AnalysisStartRequest":
        codes = {source.data_source_code for source in self.sources}
        if len(codes) != len(self.sources):
            raise ValueError("sources에 같은 원천이 두 번 있습니다.")
        if any(target.data_source_code not in codes for target in self.targets):
            raise ValueError("targets의 원천이 sources에 없습니다.")
        return self

    def targets_of(self, data_source_code: str) -> list[AnalysisTarget]:
        return [t for t in self.targets if t.data_source_code == data_source_code]
