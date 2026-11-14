"""분석 시작 알림(API 018, POST /internal/v1/analysis-runs)의 요청 모양. JSON은 camelCase.

allowedTransforms·options는 아직 쓰지 않아 버린다. connection의 비밀번호는 repr에서 빼고 저장하지 않는다.
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel


class _Wire(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, extra="ignore"
    )


class StructureOnlyColumn(_Wire):
    """값을 읽지 않고 이름·자료형만 넘기는 컬럼(원천 정책의 민감 컬럼, S15P31C106-416).

    AI 계정은 민감 컬럼을 SELECT할 수 없어 원천에서 보이지 않는다. 백엔드 카탈로그가 이름·자료형을 넘겨 속성·매핑 후보는 만들고, 예시 값·통계는 없다.
    """

    name: str
    data_type: str = ""
    comment: str | None = None  # 원천 주석(속성 이름에 쓴다). 값이 아니라 구조 정보다
    nullable: bool | None = (
        None  # 값을 못 보므로 필수 여부는 원천의 NULL 허용으로 정한다
    )
    grade: str = "RESTRICTED"  # 기록용. RESTRICTED 표시는 AI의 원천 정책 사본(source_policy)이 붙인다


class AnalysisTarget(_Wire):
    source_resource_id: int
    data_source_code: str
    resource_key: str  # 스키마.테이블 (예: mes.work_order)
    columns: list[str] | None = None  # 없으면 전체 컬럼
    source_metadata: dict = Field(default_factory=dict)
    structure_only_columns: list[StructureOnlyColumn] = Field(default_factory=list)

    @field_validator("resource_key")
    @classmethod
    def _schema_and_name(cls, value: str) -> str:
        # 점이 없으면 어느 후보에도 맞지 않아 후보 0건으로 성공한 것처럼 보인다
        schema, _, name = value.partition(".")
        if not schema.strip() or not name.strip():
            raise ValueError("resourceKey는 '스키마.이름' 형식이어야 합니다.")
        return value

    @property
    def table(self) -> str:
        return self.resource_key.split(".", 1)[1]


class AnalysisSource(_Wire):
    data_source_code: str
    source_kind: Literal["POSTGRES", "CSV"]
    connection: dict = Field(default_factory=dict, repr=False)
    db_schema: str | None = Field(default=None, alias="schema")  # 없으면 public
    path: str | None = None  # CSV 폴더


class PastDecision(_Wire):
    """승인·거절·보류된 이전 후보. 지문이 없으면 payload로 계산한다."""

    candidate_id: str | None = None
    item_kind: str | None = None
    revision: int | None = None
    fingerprint: str | None = None
    payload: dict | None = None
    decision: str  # APPROVE · REJECT · HOLD (APPROVED 등 과거형도 받음)
    reason: str | None = None


class PreviousProposal(_Wire):
    """재분석 비교용 이전 후보. 이전 후보 evidence의 candidateId·fingerprint."""

    candidate_id: str
    fingerprint: str | None = None


class AnalysisStartRequest(_Wire):
    run_id: int
    run_kind: Literal["INITIAL", "REANALYSIS"]
    targets: list[AnalysisTarget] = Field(min_length=1)
    sources: list[AnalysisSource] = Field(min_length=1)
    past_decisions: list[PastDecision] = Field(default_factory=list)
    previous_proposals: list[PreviousProposal] = Field(default_factory=list)

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
