"""분석 엔진. runner가 원천 접속과 고른 표 읽기 권한을 확인한 뒤 analyze()를 부른다.

고른 표·컬럼만 임시 폴더로 옮겨(기본키는 늘 함께) 구조·분포를 분석하고 후보를 만든다. LLM 설정이 있으면
표 의미 해석을 더하고, 없거나 실패해도 코드 분석 후보는 그대로 낸다. 메모·정비 기록 표를 고르면 글 속
언급을 DOCUMENT_LINK 후보로 더한다(LLM이 없으면 원천 키 언급만). 결과는 105 SUCCEEDED 본문의
resultSummary·proposals가 된다. 실패 사유는 AnalysisFailure로 던지면 105 FAILED의 errorMessage로 간다.

아직 하지 않는 것: 043 모델 요약·과거 결정 반영(#168), 재분석 비교(#170).
"""

import shutil
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

from app.config import get_settings
from app.extractors.notes import document_link_types, document_links
from app.llm.client import LLMClient, create_llm_client
from app.models.analysis import AnalysisStartRequest
from app.models.candidate import Candidate
from app.proposers.api_format import to_api_proposals
from app.proposers.run import generate
from app.proposers.semantics import interpret
from app.sources.base import UnknownTableError
from app.sources.csv_reader import CsvSourceReader
from app.sources.postgres_reader import PostgresSourceReader, SourceError
from app.sources.snapshot import write_snapshot

# 글을 읽어 DOCUMENT_LINK 후보를 만드는 표 (FN-AIP-02·03)
NOTE_TABLES = {"mes.handover_note", "mes.maintenance_order"}


class AnalysisFailure(Exception):
    """019에 그대로 보이는 실패 사유. 접속 정보를 넣지 않는다."""


@dataclass
class AnalysisResult:
    summary: dict = field(default_factory=dict)
    proposals: list[dict] = field(default_factory=list)


# 원천 코드 → 열린 PostgreSQL 읽기 창구 또는 CSV 폴더
Sources = dict[str, PostgresSourceReader | Path]


def get_llm() -> LLMClient | None:
    """LLM 설정이 없거나 꺼 두면 None. 그때는 코드 분석만 한다."""
    settings = get_settings()
    return create_llm_client(settings) if settings.llm_ready else None


def analyze(
    request: AnalysisStartRequest,
    sources: Sources,
    llm: LLMClient | None = None,
) -> AnalysisResult:
    if llm is None:
        llm = get_llm()
    workdir = Path(tempfile.mkdtemp(prefix="ai-run-"))
    try:
        root = _snapshot(request, sources, workdir)
        return _analyze(request, root, llm)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)  # 옮겨 온 원천 값은 남기지 않는다


def _prefix(resource_key: str) -> str:
    """원천.테이블의 원천 부분(예: mes). 후보의 resourceKey가 이 이름으로 시작한다."""
    return resource_key.split(".", 1)[0]


def _snapshot(request: AnalysisStartRequest, sources: Sources, workdir: Path) -> Path:
    """고른 표·컬럼과 등록 메타데이터를 원천별 폴더로 옮긴다."""
    for code, source in sources.items():
        targets = request.targets_of(code)
        if not targets:
            continue
        for prefix in {_prefix(t.resource_key) for t in targets}:
            chosen = [t for t in targets if _prefix(t.resource_key) == prefix]
            reader = (
                CsvSourceReader(prefix, source) if isinstance(source, Path) else source
            )
            try:
                write_snapshot(
                    reader,
                    workdir,
                    {t.table: t.columns or None for t in chosen},
                    {t.table: t.source_metadata for t in chosen},
                    name=prefix,
                )
            except (SourceError, UnknownTableError) as error:
                raise AnalysisFailure(f"{error} ({code})") from None
    return workdir


def _tables_of(candidate: Candidate) -> set[str]:
    cid = candidate.candidate_id
    for prefix in ("OT-", "OM-"):
        if cid.startswith(prefix):
            return {cid[len(prefix) :]}
    for prefix in ("P-", "PM-"):
        if cid.startswith(prefix):
            return {cid[len(prefix) :].rsplit(".", 1)[0]}
    return {".".join(e.locator.split(".")[:2]) for e in candidate.evidence}


def _with_source_codes(value, codes: dict[str, str]):
    """후보 안의 sourceId를 원천 코드(data_source.code, 예: MES_MAIN)로 바꾼다. resourceKey는 그대로 둔다."""
    if isinstance(value, dict):
        return {
            k: codes.get(v, v)
            if k == "sourceId" and isinstance(v, str)
            else _with_source_codes(v, codes)
            for k, v in value.items()
        }
    if isinstance(value, list):
        return [_with_source_codes(v, codes) for v in value]
    return value


def _analyze(
    request: AnalysisStartRequest, root: Path, llm: LLMClient | None
) -> AnalysisResult:
    scope = {t.resource_key for t in request.targets}
    kept, dropped = generate(root)
    kept = [c for c in kept if _tables_of(c) & scope]
    llm_calls, llm_failed, credits = 0, 0, 0.0
    if llm is not None and kept:
        kept, report = interpret(root, kept, llm, only=scope)
        llm_calls, credits = report["tables"], report["credits_used"]
        llm_failed = len(report["failed"])
    documents, links = 0, []
    if scope & NOTE_TABLES:
        # LLM이 있으면 설비 별명·증상·부정·추정도 뽑는다(10개 문서씩 한 번 호출)
        documents, links, calls, failed = document_links(root, llm)
        llm_calls += len(calls)
        llm_failed += len(failed)
        credits += sum(call["credits"] for call in calls)
    kept += links + document_link_types(links)

    codes = {_prefix(t.resource_key): t.data_source_code for t in request.targets}
    # 표 하나·관계 하나를 검토 한 건으로 묶어 보낸다
    proposals = [_with_source_codes(p, codes) for p in to_api_proposals(kept)]
    summary = {
        "analyzedTables": sorted(scope),
        "proposals": len(proposals),
        "holdRecommended": sum(p["evidence"]["holdRecommended"] for p in proposals),
        "droppedNoEvidence": len(dropped),
        "documents": documents,
        "documentLinks": len(links),
        "llmUsed": llm_calls > llm_failed,
        "llmSkipReason": _llm_skip_reason(llm, llm_calls, llm_failed),
        "llmCalls": llm_calls,
        "llmFailedCalls": llm_failed,
        "llmCredits": round(credits, 2),
    }
    return AnalysisResult(summary=summary, proposals=proposals)


def _llm_skip_reason(llm: LLMClient | None, calls: int, failed: int) -> str | None:
    """LLM 없이 코드 분석만 한 이유. 화면(019)에서 결과를 볼 때 함께 보인다."""
    if llm is None:
        return "LLM 설정 없음"
    if calls and failed == calls:
        return "LLM 호출이 모두 실패함"
    return None
