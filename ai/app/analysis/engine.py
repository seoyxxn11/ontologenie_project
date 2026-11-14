"""분석 엔진. runner가 원천 접속과 고른 표 읽기 권한을 확인한 뒤 analyze()를 부른다.

LLM이 없거나 실패해도 코드 분석 후보는 그대로 낸다. AnalysisFailure의 사유는 105 FAILED의 errorMessage가 된다.
"""

import logging
import re
import shutil
import tempfile
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from app.analysis.backend import BackendError, fetch_model_summary
from app.config import get_settings
from app.extractors.notes import _equipment as note_equipment
from app.extractors.notes import (
    document_link_types,
    document_links,
    load_documents,
)
from app.extractors.notes import estimate_credits as estimate_notes
from app.llm.client import LLMClient, create_llm_client
from app.models.analysis import AnalysisStartRequest
from app.models.candidate import Candidate
from app.proposers.api_format import to_api_proposals
from app.proposers.evidence import verify
from app.proposers.existing import change_counts, filter_existing, skip_past_decisions
from app.proposers.links import Tables, business_cardinality
from app.proposers.llm_links import estimate_credits as estimate_cross_links
from app.proposers.llm_links import prepare as prepare_cross_links
from app.proposers.llm_links import propose as cross_links
from app.proposers.reanalysis import mark_changes
from app.proposers.run import generate
from app.proposers.semantics import estimate_credits as estimate_tables
from app.proposers.semantics import interpret, prepare
from app.sources.base import UnknownTableError
from app.sources.csv_reader import CsvSourceReader
from app.sources.postgres_reader import PostgresSourceReader, SourceError
from app.sources.snapshot import SnapshotReport, write_snapshot

log = logging.getLogger(__name__)

# 글을 읽어 DOCUMENT_LINK 후보를 만드는 표
NOTE_TABLES = {"mes.handover_note", "mes.maintenance_order"}
# 원천 값을 옮겨 두는 임시 폴더 이름 앞부분. 강제 종료로 남은 폴더는 서버가 시작할 때 지운다
WORKDIR_PREFIX = "ai-run-"


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


def get_model_summary() -> tuple[dict | None, str | None]:
    """(모델 요약, 못 받은 이유). 내부 토큰이 없으면 부르지 않고, 못 받아도 분석은 한다."""
    settings = get_settings()
    if not settings.internal_token:
        return None, "내부 토큰 없음"
    try:
        return fetch_model_summary(settings), None
    except BackendError as error:
        return None, f"모델 요약을 받지 못함({error})"


def analyze(
    request: AnalysisStartRequest,
    sources: Sources,
    llm: LLMClient | None = None,
    model_summary: dict | None = None,
) -> AnalysisResult:
    check_targets(request)
    if llm is None:
        llm = get_llm()
    summary_skip = None
    if model_summary is None:
        model_summary, summary_skip = get_model_summary()
    workdir = Path(tempfile.mkdtemp(prefix=WORKDIR_PREFIX))
    try:
        try:
            report = _snapshot(request, sources, workdir)
        finally:
            # 원천 값은 임시 폴더로 옮겼으니 LLM 단계까지 원천 연결을 잡아 두지 않는다
            _close_readers(sources)
        result = _analyze(request, workdir, llm, get_settings().llm_credit_limit)
        # 확정 모델(043)과 같은 후보는 빼고, 재분석이면 추가·변경·유지를 표시한다
        reanalysis = request.run_kind == "REANALYSIS"
        result.proposals, skipped = filter_existing(
            result.proposals, model_summary, mark_changes=reanalysis
        )
        # pastDecisions가 없으면 그대로 둔다
        result.proposals, decided = skip_past_decisions(
            result.proposals,
            [d.model_dump(by_alias=True) for d in request.past_decisions],
        )
        result.summary["pastDecisionSkipped"] = decided
        result.summary["proposals"] = len(result.proposals)
        result.summary["holdRecommended"] = sum(
            p["evidence"]["holdRecommended"] for p in result.proposals
        )
        result.summary["existingSkipped"] = skipped
        result.summary["modelSummarySkipReason"] = summary_skip
        # 이전 후보가 있으면 그 기준, 없으면 확정 모델(043) 기준으로 표시한다
        if reanalysis and request.previous_proposals:
            result.summary["changeBasis"] = "PREVIOUS_PROPOSALS"
            result.summary["changeCounts"] = mark_changes(
                result.proposals,
                [p.model_dump(by_alias=True) for p in request.previous_proposals],
            )
        elif reanalysis and model_summary:
            result.summary["changeBasis"] = "MODEL_SUMMARY"
            result.summary["changeCounts"] = change_counts(result.proposals, skipped)
        else:
            result.summary["changeBasis"] = None
            result.summary["changeCounts"] = None
        result.summary["missingColumns"] = report.missing_columns
        result.summary["sampledTables"] = report.sampled_tables
        return result
    finally:
        shutil.rmtree(workdir, ignore_errors=True)  # 옮겨 온 원천 값은 남기지 않는다


def remove_stale_workdirs(root: Path | None = None) -> int:
    """강제 종료로 남은 임시 폴더(원천 값 복사본)를 지운다. 분석이 돌지 않는 서버 시작 때만 부른다."""
    removed = 0
    for path in Path(root or tempfile.gettempdir()).glob(f"{WORKDIR_PREFIX}*"):
        if path.is_dir():
            shutil.rmtree(path, ignore_errors=True)
            removed += not path.exists()
    return removed


def _prefix(resource_key: str) -> str:
    """원천.테이블의 원천 부분(예: mes). 후보의 resourceKey가 이 이름으로 시작한다."""
    return resource_key.split(".", 1)[0]


def check_targets(request: AnalysisStartRequest) -> None:
    """서로 다른 원천이 같은 스키마 이름(resourceKey 앞부분)을 쓰면 막는다.

    임시 폴더와 후보 ID·resourceKey가 스키마 이름으로 갈려서, 겹치면 표 정의가 덮어써지고 sourceId가 틀린다.
    """
    owners: dict[str, set[str]] = {}
    for target in request.targets:
        owners.setdefault(_prefix(target.resource_key), set()).add(
            target.data_source_code
        )
    for prefix, codes in sorted(owners.items()):
        if len(codes) > 1:
            raise AnalysisFailure(
                f"서로 다른 원천이 같은 스키마 이름({prefix})을 써서 함께 분석할 수 없습니다: "
                f"{', '.join(sorted(codes))}. 원천별로 나눠 분석해 주세요"
            )


def _close_readers(sources: Sources) -> None:
    for source in sources.values():
        if isinstance(source, PostgresSourceReader):
            source.close()


def _snapshot(
    request: AnalysisStartRequest, sources: Sources, workdir: Path
) -> SnapshotReport:
    """고른 표·컬럼과 등록 메타데이터를 원천별 폴더로 옮긴다."""
    report = SnapshotReport()
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
                part = write_snapshot(
                    reader,
                    workdir,
                    {t.table: t.columns or None for t in chosen},
                    {t.table: t.source_metadata for t in chosen},
                    name=prefix,
                    structure_only={
                        t.table: t.structure_only_columns
                        for t in chosen
                        if t.structure_only_columns
                    },
                )
            except (SourceError, UnknownTableError) as error:
                raise AnalysisFailure(f"{error} ({code})") from None
            report.missing_columns.update(part.missing_columns)
            report.sampled_tables.update(part.sampled_tables)
    return report


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


def _expected_credits(
    root: Path, kept: list[Candidate], scope: set[str], model: str | None
) -> tuple[float, Tables]:
    """LLM을 부르기 전 이번 분석의 예상 크레딧(표 의미 해석 + 메모 추출)."""
    expected = estimate_tables(prepare(root, kept, only=scope), model=model)
    cross_tables, cross_prompts = prepare_cross_links(root, kept)
    expected += estimate_cross_links(cross_prompts, model=model)
    if scope & NOTE_TABLES:
        docs = load_documents(root)
        if docs:
            expected += estimate_notes(docs, note_equipment(root), model=model)
    return expected, cross_tables


def _analyze(
    request: AnalysisStartRequest,
    root: Path,
    llm: LLMClient | None,
    credit_limit: float = 0,
) -> AnalysisResult:
    scope = {t.resource_key for t in request.targets}
    kept, dropped = generate(root)
    kept = [c for c in kept if _tables_of(c) & scope]
    llm_calls, llm_failed, credits, llm_rejected, llm_cross = 0, 0, 0.0, 0, 0
    # LLM이 연속으로 응답하지 않아 부르지 않고 건너뛴 호출 수. 부른 수·실패 수에는 넣지 않는다
    llm_skipped = 0
    over_limit = None
    cross_tables = (
        None  # 예상 크레딧을 셀 때 만든 원천 분석을 시스템 간 연결에서 다시 쓴다
    )
    if llm is not None and credit_limit > 0:
        expected, cross_tables = _expected_credits(
            root, kept, scope, getattr(llm, "model", None)
        )
        if expected > credit_limit:
            # 크레딧을 아끼려고 이번 분석은 LLM 없이 코드 분석만 한다
            over_limit = (
                f"예상 크레딧 {expected:,.0f}이 한도 {credit_limit:,.0f}를 넘음"
            )
            llm = None
    if llm is not None and kept:
        kept, report = interpret(root, kept, llm, only=scope)
        llm_calls, credits = report["calls"], report["credits_used"]
        llm_skipped = report["skipped"]
        llm_failed = len(report["failed"]) - llm_skipped
        _log_llm_failures(request.run_id, "표 의미 해석", report["failed"])
        llm_rejected = len(report["rejected_links"])
        # 규칙이 못 이은 시스템 간 참조 컬럼은 LLM에게 묻고 값으로 확인한다(보류로만 낸다)
        cross, cross_report = cross_links(root, kept, llm, cross_tables)
        cross_kept, cross_dropped = verify(root, cross)
        kept += cross_kept
        dropped += cross_dropped
        llm_calls += cross_report["calls"]
        llm_skipped += cross_report["skipped"]
        llm_failed += len(cross_report["failed"]) - cross_report["skipped"]
        _log_llm_failures(request.run_id, "시스템 간 연결", cross_report["failed"])
        credits += cross_report["credits_used"]
        llm_cross = len([c for c in cross_kept if c.payload.kind.value == "LINK_RULE"])
    documents, links = 0, []
    if scope & NOTE_TABLES:
        # LLM이 있으면 설비 별명·증상·부정·추정도 뽑는다(10개 문서씩 한 번 호출)
        documents, links, calls, failed = document_links(root, llm)
        skipped = sum(bool(f.get("skipped")) for f in failed)
        llm_calls += len(calls)
        llm_skipped += skipped
        llm_failed += len(failed) - skipped
        _log_llm_failures(request.run_id, "메모 추출", failed)
        credits += sum(call["credits"] for call in calls)
    # 표 후보와 같이 원천 위치를 다시 확인해 근거에 verified를 채운다
    notes_kept, notes_dropped = verify(root, links + document_link_types(links, root))
    kept += notes_kept
    dropped += notes_dropped

    kept = business_cardinality(kept)
    codes = {_prefix(t.resource_key): t.data_source_code for t in request.targets}
    proposals = [_with_source_codes(p, codes) for p in to_api_proposals(kept)]
    summary = {
        "analyzedTables": sorted(scope),
        "proposals": len(proposals),
        "holdRecommended": sum(p["evidence"]["holdRecommended"] for p in proposals),
        "droppedNoEvidence": len(dropped),
        "documents": documents,
        "documentLinks": sum(c.candidate_id.startswith("DL-") for c in notes_kept),
        "llmUsed": llm_calls > llm_failed,
        "llmSkipReason": over_limit or _llm_skip_reason(llm, llm_calls, llm_failed),
        "llmCalls": llm_calls,
        "llmFailedCalls": llm_failed,
        "llmSkippedCalls": llm_skipped,
        "llmCredits": round(credits, 2),
        # 규칙도 보류로 두고 LLM도 업무상 맞지 않다고 봐서 보내지 않은 관계 수
        "llmRejectedLinks": llm_rejected,
        # LLM이 제안하고 값으로 확인한 시스템 간 연결 수(모두 보류 권고)
        "llmCrossLinks": llm_cross,
    }
    return AnalysisResult(summary=summary, proposals=proposals)


# LLM 실패 문구(app/llm/client.py) → 로그에 남길 종류. 문구 뒤에 붙는 응답 본문은 남기지 않는다
LLM_HTTP_STATUS = re.compile(r"요청 실패 \(HTTP (\d{3})\)")
LLM_NO_ANSWER = re.compile(r"응답이 없습니다: (\w+)")
LLM_ERROR_KINDS = (
    ("남은 호출을 건너뜀", "LLM 끊김"),  # app/llm/outage.py
    ("접속하지 못했습니다", "접속 실패"),
    ("JSON이 아닌", "JSON 아님"),
    ("글자가 없습니다", "빈 응답"),
    ("응답 오류", "응답 오류"),
    ("validation error", "답 형식 오류"),
)


def llm_error_kind(error: object) -> str:
    """실패 목록의 오류 문구에서 종류(HTTP 상태·시간 초과 등)만 꺼낸다."""
    text = error if isinstance(error, str) else ""
    if match := LLM_HTTP_STATUS.search(text):
        return f"HTTP {match[1]}"
    if match := LLM_NO_ANSWER.search(text):
        return f"응답 없음({match[1]})"
    return next((kind for part, kind in LLM_ERROR_KINDS if part in text), "기타")


def _log_llm_failures(run_id: int, stage: str, failed: list[dict]) -> None:
    """LLM 실패 원인을 실행·단계·종류만 남긴다. 응답 본문·프롬프트·키는 남기지 않는다."""
    if not failed:
        return
    kinds = Counter(llm_error_kind(f.get("error")) for f in failed)
    log.warning(
        "분석 실행 %s LLM %s 실패 %d건: %s",
        run_id,
        stage,
        len(failed),
        ", ".join(f"{kind} {count}건" for kind, count in sorted(kinds.items())),
    )


def _llm_skip_reason(llm: LLMClient | None, calls: int, failed: int) -> str | None:
    """LLM 없이 코드 분석만 한 이유. 화면(019)에서 결과를 볼 때 함께 보인다."""
    if llm is None:
        return "LLM 설정 없음"
    if calls and failed == calls:
        return "LLM 호출이 모두 실패함"
    return None
