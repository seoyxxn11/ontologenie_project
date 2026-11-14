from enum import Enum
from typing import Annotated, Literal

from pydantic import BaseModel, Field, model_validator

from app.models.source import ColumnRef
from app.rules.transforms import TransformStep


class Sensitivity(str, Enum):
    """ERD v2 property_definition.sensitivity 값."""

    NORMAL = "NORMAL"
    RESTRICTED = "RESTRICTED"


class DataType(str, Enum):
    """온톨로지 ERD v2의 property_definition.data_type 값."""

    STRING = "STRING"
    INTEGER = "INTEGER"
    DECIMAL = "DECIMAL"
    DATE = "DATE"
    TIME = "TIME"
    DATETIME = "DATETIME"
    BOOLEAN = "BOOLEAN"
    JSON = "JSON"


Cardinality = Literal["ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY"]
# master 기준 정보 · transaction 거래 문서 · detail 문서 상세 줄 · history 발생 기록 · snapshot 현재 상태
ObjectCategory = Literal["master", "transaction", "detail", "history", "snapshot"]


class ItemKind(str, Enum):
    """운영 ERD proposal_item.item_kind 값."""

    OBJECT_TYPE = "OBJECT_TYPE"
    PROPERTY = "PROPERTY"
    LINK_TYPE = "LINK_TYPE"
    OBJECT_MAPPING = "OBJECT_MAPPING"
    PROPERTY_MAPPING = "PROPERTY_MAPPING"
    LINK_RULE = "LINK_RULE"
    DOCUMENT_LINK = "DOCUMENT_LINK"


class Evidence(BaseModel):
    """후보의 근거 한 건. verified는 위치·값이 원천에 있는지만 뜻하고 업무 의미까지 보장하지 않는다."""

    source_id: str
    locator: str
    snippet: str
    # 원천에서 다시 찾아볼 값. 비어 있으면 위치(표·컬럼)만 확인한다
    values: list[str] = Field(default_factory=list)
    verified: bool = False


class ScoreItem(BaseModel):
    label: str
    weight: float = Field(ge=0, le=1)
    # 같은 근거에서 나온 신호는 같은 묶음. 묶음 안에서는 가장 큰 점수 하나만 더한다(중복 가산 금지)
    group: str | None = None


class Confidence(BaseModel):
    """AI 신뢰도(0~1). 근거별 실제 정답 비율(confidence.py)이고 보류면 상한을 둔다. LLM이 말한 숫자는 쓰지 않는다."""

    items: list[ScoreItem] = Field(default_factory=list)
    cap: float | None = Field(default=None, ge=0, le=1)
    cap_reason: str | None = None

    @property
    def raw_total(self) -> float:
        best: dict[str, float] = {}
        loose = 0.0
        for item in self.items:
            if item.group is None:
                loose += item.weight
            else:
                best[item.group] = max(best.get(item.group, 0.0), item.weight)
        return round(loose + sum(best.values()), 4)

    @property
    def total(self) -> float:
        raw = self.raw_total
        return round(min(raw, self.cap) if self.cap is not None else raw, 4)

    @model_validator(mode="after")
    def _total_within_one(self) -> "Confidence":
        if self.raw_total > 1:
            raise ValueError(f"신뢰도 합이 1을 넘습니다: {self.raw_total}")
        return self

    def describe(self) -> str:
        parts = " + ".join(f"{item.label} {item.weight:g}" for item in self.items)
        text = f"{parts} = {self.raw_total:g}" if parts else "0"
        if self.cap is not None and self.raw_total > self.cap:
            text += f" → 상한 {self.cap:g} ({self.cap_reason})"
        return text


class ObjectTypePayload(BaseModel):
    kind: Literal[ItemKind.OBJECT_TYPE] = ItemKind.OBJECT_TYPE
    code: str
    name: str
    description: str = ""
    synonyms: list[str] = Field(default_factory=list)
    note: str | None = None
    # 표 종류 제안. 운영 ERD object_type에는 없는 값이라 검토 참고용이다
    category: ObjectCategory | None = None
    # 조회 그룹 기본값(object_type.access_group, 392). 원천 업무 영역으로 정하고, 정하지 못하면 None(승인하면 비공개)
    access_group: str | None = None


class PropertyPayload(BaseModel):
    kind: Literal[ItemKind.PROPERTY] = ItemKind.PROPERTY
    object_type_code: str
    code: str
    name: str
    data_type: DataType
    description: str = ""
    is_identifier: bool = False
    is_required: bool = False
    unit: str | None = None
    # 개인정보면 RESTRICTED. 화면·LLM 입력에서 값을 가린다
    sensitivity: Sensitivity = Sensitivity.NORMAL


class LinkTypePayload(BaseModel):
    kind: Literal[ItemKind.LINK_TYPE] = ItemKind.LINK_TYPE
    code: str
    name: str
    source_type_code: str
    target_type_code: str
    cardinality: Cardinality
    description: str = ""


class ObjectMappingPayload(BaseModel):
    """원천 표 하나에서 어떤 객체 유형을 만들지."""

    kind: Literal[ItemKind.OBJECT_MAPPING] = ItemKind.OBJECT_MAPPING
    object_type_code: str
    source_id: str
    resource_key: str
    key_columns: list[str] = Field(min_length=1)


class PropertyMappingPayload(BaseModel):
    """원천 컬럼 값을 어떤 속성에 넣을지."""

    kind: Literal[ItemKind.PROPERTY_MAPPING] = ItemKind.PROPERTY_MAPPING
    object_type_code: str
    property_code: str
    source_column: ColumnRef
    transform_steps: list[TransformStep] = Field(default_factory=list)


class RuleType(str, Enum):
    """연결 규칙을 어떤 방식으로 찾았는지 (설명·평가용)."""

    SAME_VALUE = "same_value"
    TRANSFORM = "transform"
    MAPPING_COLUMN = "mapping_column"


class LinkRuleCondition(BaseModel):
    """두 객체의 속성을 각각 변환한 뒤 같은지 비교한다 (ERD link_mapping_condition)."""

    source_property: str = Field(description="'객체유형코드.속성코드'")
    target_property: str
    operator: Literal["EQ"] = "EQ"
    source_transforms: list[TransformStep] = Field(default_factory=list)
    target_transforms: list[TransformStep] = Field(default_factory=list)


class SourceFilter(BaseModel):
    """출발 객체 중 이 조건에 맞는 것만 규칙을 적용한다 (ERD link_mapping.source_filter). 예: 정비 대상 구분 = 금형"""

    property: str = Field(description="'객체유형코드.속성코드'")
    equals: str


class LinkRulePayload(BaseModel):
    """객체를 잇는 규칙. conditions가 둘 이상이면 모두 같아야 한다(복합키)."""

    kind: Literal[ItemKind.LINK_RULE] = ItemKind.LINK_RULE
    code: str
    link_type_code: str
    pattern: RuleType
    conditions: list[LinkRuleCondition] = Field(min_length=1)
    source_filter: SourceFilter | None = None
    description: str = ""

    @model_validator(mode="after")
    def _pattern_matches_transforms(self) -> "LinkRulePayload":
        has_transforms = any(
            c.source_transforms or c.target_transforms for c in self.conditions
        )
        if self.pattern == RuleType.TRANSFORM and not has_transforms:
            raise ValueError("규칙 변환(transform) 후보에는 변환 단계가 필요합니다.")
        if self.pattern != RuleType.TRANSFORM and has_transforms:
            raise ValueError("변환 단계가 있으면 pattern은 transform이어야 합니다.")
        return self


class DocumentLinkPayload(BaseModel):
    """문서(메모·정비 기록)의 언급을 실제 객체에 잇는 후보. 예: 메모 '3호기' → 설비 P103."""

    kind: Literal[ItemKind.DOCUMENT_LINK] = ItemKind.DOCUMENT_LINK
    link_type_code: str
    document_id: str
    span_start: int = Field(ge=0)
    span_end: int
    mention_text: str = Field(min_length=1)
    target_object_ref: str
    # API 공통 규칙: OBSERVED 확인, ESTIMATED 추정. 부정 표현만 있는 언급은 연결하지 않는다
    expression_kind: Literal["OBSERVED", "ESTIMATED"] = "OBSERVED"

    @model_validator(mode="after")
    def _span_length(self) -> "DocumentLinkPayload":
        if self.span_end - self.span_start != len(self.mention_text):
            raise ValueError("원문 위치 길이가 mention_text와 다릅니다.")
        return self


CandidatePayload = Annotated[
    ObjectTypePayload
    | PropertyPayload
    | LinkTypePayload
    | ObjectMappingPayload
    | PropertyMappingPayload
    | LinkRulePayload
    | DocumentLinkPayload,
    Field(discriminator="kind"),
]


class Observation(BaseModel):
    """언급에 붙은 증상·상태 표현과 그 단정 정도. 위치는 원문 글자 위치(end 미포함)."""

    text: str = Field(min_length=1)
    span_start: int = Field(ge=0)
    span_end: int
    assertion: Literal["AFFIRMED", "NEGATED", "UNCERTAIN"]


class TimeExpression(BaseModel):
    """글 속 시각 표현. normalized는 원천에서 기준을 알 때만 채우고, basis에 그 근거나 못 정한 이유를 둔다."""

    text: str = Field(min_length=1)
    span_start: int = Field(ge=0)
    span_end: int
    normalized: str | None = None
    basis: str | None = None


class DocumentContext(BaseModel):
    """DOCUMENT_LINK 후보를 원문과 비교해 검토하도록 함께 보내는 원문·관찰 표현·시각."""

    original_text: str = Field(min_length=1)
    observations: list[Observation] = Field(default_factory=list)
    times: list[TimeExpression] = Field(default_factory=list)
    # 화면 제목용 이름. 메모는 '10/29 야간 인수인계', 대상은 원천의 이름 컬럼 값('1라인 3호 프레스'). 없으면 번호로 보인다
    document_label: str | None = None
    target_labels: dict[str, str] = Field(default_factory=dict)

    @model_validator(mode="after")
    def _spans_in_text(self) -> "DocumentContext":
        for item in [*self.observations, *self.times]:
            if self.original_text[item.span_start : item.span_end] != item.text:
                raise ValueError(f"'{item.text}'의 위치가 원문과 다릅니다.")
        return self


class CandidateOption(BaseModel):
    """같은 대상에 후보가 여럿일 때의 선택지 (운영 ERD proposal_option)."""

    option_no: int = Field(ge=1)
    payload: CandidatePayload
    evidence: list[Evidence] = Field(min_length=1)
    confidence: Confidence | None = None


class Candidate(BaseModel):
    """AI 제안기가 Backend로 넘기는 후보 한 건 (운영 ERD proposal_item). 검토·반영 상태는 Backend가 관리한다."""

    candidate_id: str
    title: str
    payload: CandidatePayload
    reason: str = Field(min_length=1, description="제안 이유 설명 (FN-AIP-05)")
    evidence: list[Evidence] = Field(min_length=1)
    confidence: Confidence | None = Field(
        default=None, description="None은 미산출. 0과 구분한다"
    )
    options: list[CandidateOption] = Field(default_factory=list)
    # rule: 코드 규칙으로만 만듦 · llm: LLM 해석 결과 · rule+llm: 규칙 결과에 LLM 해석을 더함
    method: Literal["rule", "llm", "rule+llm"] = "rule"
    # AI가 정하는 검토 상태는 대기(PENDING)와 보류 권고(HELD)뿐. 승인·거절은 Backend가 한다
    review_status: Literal["PENDING", "HELD"] = "PENDING"
    hold_reason: str | None = None
    # LLM 판단. 신뢰도 점수에는 넣지 않는다(LLM 동의만으로 점수가 오르면 안 되므로)
    llm_judgement: str | None = None
    # DOCUMENT_LINK만: 원문과 관찰 표현
    document: DocumentContext | None = None

    @property
    def item_kind(self) -> ItemKind:
        return self.payload.kind

    @property
    def ai_confidence(self) -> float | None:
        return self.confidence.total if self.confidence else None

    @model_validator(mode="after")
    def _options_consistent(self) -> "Candidate":
        if len(self.options) == 1:
            raise ValueError("선택지는 2개 이상일 때만 둡니다.")
        if any(option.payload.kind != self.payload.kind for option in self.options):
            raise ValueError("선택지는 대표 후보와 같은 종류여야 합니다.")
        numbers = [option.option_no for option in self.options]
        if len(numbers) != len(set(numbers)):
            raise ValueError("선택지 번호가 중복됩니다.")
        return self
