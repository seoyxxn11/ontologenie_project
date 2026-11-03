"""원천 폴더(CSV)를 읽어 온톨로지 후보 JSON을 만든다. 백엔드 없이 실행한다.

    .\\.venv\\Scripts\\python.exe -m app.proposers.run data/data-v1.0 --out results/proposals-v1.0.json

후보: 객체 유형·객체 매핑·속성·속성 매핑·관계 유형·연결 규칙. 기본은 코드 규칙만 쓰고,
--llm-credit-limit을 주면 GMS로 표마다 의미 해석을 더한다(크레딧 사용).
"""

import argparse
import json
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

from app.analyzers.structured import analyze_sources
from app.config import get_settings
from app.llm.client import LLMClient, create_llm_client
from app.models.candidate import Candidate
from app.proposers.api_format import to_api
from app.proposers.evidence import verify
from app.proposers.links import propose_links
from app.proposers.objects import propose_objects
from app.proposers.semantics import estimate_credits, interpret, prepare

NOT_YET = [
    "LLM 의미 해석 없이 실행: 모든 후보가 method=rule이고 표 종류·개인정보는 규칙 판단만 들어 있다",
]


def generate(root: Path) -> tuple[list[Candidate], list[Candidate]]:
    """(내보낼 후보, 근거를 원천에서 확인하지 못해 뺀 후보)."""
    analysis = analyze_sources(root)
    candidates = propose_objects(root, analysis) + propose_links(root, analysis)
    return verify(root, candidates)


def api_result(kept: list[Candidate]) -> dict:
    """105로 보낼 때와 같은 형식의 결과 (명령줄 실행에서 파일로 볼 때)."""
    return {
        "status": "SUCCEEDED",
        "resultSummary": {
            "proposals": len(kept),
            "holdRecommended": sum(c.review_status == "HELD" for c in kept),
        },
        "proposals": [to_api(c) for c in kept],
    }


def run(root: Path, llm: LLMClient | None = None, only: set[str] | None = None) -> dict:
    kept, dropped = generate(root)
    llm_report = None
    if llm is not None:
        kept, llm_report = interpret(root, kept, llm, only)
    return {
        "source": str(root),
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "summary": {
            "by_kind": dict(Counter(c.payload.kind.value for c in kept)),
            "held": sum(c.review_status == "HELD" for c in kept),
            "dropped_no_evidence": len(dropped),
        },
        "llm": llm_report,
        "not_yet": NOT_YET if llm is None else [],
        "candidates": [
            {**c.model_dump(mode="json"), "ai_confidence": c.ai_confidence}
            for c in kept
        ],
        "dropped": [c.candidate_id for c in dropped],
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="온톨로지 후보 생성 (CSV 입력)")
    parser.add_argument("root", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument(
        "--api-out", type=Path, help="Spring Boot 연동 형식(camelCase)으로도 저장"
    )
    parser.add_argument(
        "--llm-estimate", action="store_true", help="GMS 호출 없이 예상 크레딧만 출력"
    )
    parser.add_argument(
        "--llm-credit-limit",
        type=float,
        help="GMS로 의미 해석을 실행한다. 예상 크레딧이 이 값보다 크면 멈춘다(승인 후에만 사용)",
    )
    parser.add_argument(
        "--llm-tables", nargs="*", help="의미 해석할 표만 (예: mes.worker). 없으면 전체"
    )
    args = parser.parse_args(argv)
    only = set(args.llm_tables) if args.llm_tables else None
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    llm = None
    if args.llm_estimate or args.llm_credit_limit is not None:
        kept, _ = generate(args.root)
        prompts = prepare(args.root, kept, only)
        estimate = estimate_credits(prompts)
        print(f"LLM 의미 해석 예상 크레딧: {estimate:.1f} (표 {len(prompts)}개)")
        if args.llm_estimate:
            return 0
        if estimate > args.llm_credit_limit:
            print(f"예상 크레딧이 한도 {args.llm_credit_limit}보다 커서 멈춥니다.")
            return 1
        llm = create_llm_client(get_settings())
    result = run(args.root, llm, only)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    if args.api_out:
        kept = [Candidate.model_validate(c) for c in result["candidates"]]
        args.api_out.write_text(
            json.dumps(api_result(kept), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    print(json.dumps(result["summary"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
