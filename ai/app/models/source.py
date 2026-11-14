from pydantic import BaseModel, Field


class ColumnRef(BaseModel):
    """원천의 특정 컬럼 위치. 예: mes.jobs.order_code"""

    source_id: str
    table: str
    column: str
    # source_resource.resource_key와 같은 "원천.표"(예: erp.attendance). 백엔드는 이 값으로 표를 찾는다
    resource_key: str | None = None

    def __str__(self) -> str:
        return f"{self.source_id}.{self.table}.{self.column}"


class ColumnSchema(BaseModel):
    name: str
    declared_type: str | None = None
    comment: str | None = None
    is_primary_key: bool = False
    # 값을 읽지 않고 이름·자료형만 받은 컬럼(민감 컬럼, 416). 값 통계 대신 nullable로 필수 여부를 정한다
    values_read: bool = True
    nullable: bool | None = None


class TableSchema(BaseModel):
    source_id: str
    name: str
    description: str | None = None
    note: str | None = None  # 플랫폼에 등록한 표 동의어·메모 (LLM 참고용)
    columns: list[ColumnSchema] = Field(default_factory=list)

    @property
    def primary_key(self) -> list[str]:
        return [column.name for column in self.columns if column.is_primary_key]

    def column(self, name: str) -> ColumnSchema:
        for column in self.columns:
            if column.name == name:
                return column
        raise KeyError(f"{self.source_id}.{self.name}에 {name} 컬럼이 없습니다.")
