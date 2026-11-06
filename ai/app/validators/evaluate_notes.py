"""메모·정비 기록 추출 평가 (FN-AIP-02·03, DAT-05).

    .\\.venv\\Scripts\\python.exe -m app.validators.evaluate_notes results/notes-v1.0.json data/gold-v1.0 data/data-v1.0

- 개체: 같은 문서·유형·원문 위치(start·end가 정확히 같음)면 맞음. 유형별 정밀도·재현율.
  위치가 겹치기만 하면 맞음으로 보는 부분 일치도 따로 낸다(경계 차이 확인용).
- 연결: 맞은 개체 중 연결 결과(연결·모호·미연결과 대상)가 정답과 같은 비율.
- 부정·추정, 시각 절대값: 맞은 개체에서 따로 센다.
- 설비 ID 연결만 따로: 정답 언급 글자를 그대로 넣었을 때 연결 결과(추출 실력과 분리).
"""

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

from app.extractors.equipment import load_equipment, resolve
from app.sources.csv_reader import CsvSourceReader


def _key(doc_id, e):
    return (doc_id, e["type"], e["start"], e["end"])


def _res(e):
    r = e["resolution"]
    return (r["status"], r.get("ref"), tuple(sorted(r.get("candidates") or [])))


def evaluate(pred: list[dict], gold: list[dict], data_root: Path | None = None) -> dict:
    gold_e = {_key(d["doc_id"], e): e for d in gold for e in d["entities"]}
    pred_e = {_key(d["doc_id"], e): e for d in pred for e in d["entities"]}
    types = sorted({k[1] for k in gold_e} | {k[1] for k in pred_e})
    by_type = {}
    for t in types:
        g = {k for k in gold_e if k[1] == t}
        p = {k for k in pred_e if k[1] == t}
        hit = g & p
        by_type[t] = {
            "gold": len(g),
            "pred": len(p),
            "correct": len(hit),
            "precision": round(len(hit) / len(p), 4) if p else None,
            "recall": round(len(hit) / len(g), 4) if g else None,
        }
    # 부분 일치: 같은 문서·유형이고 위치가 겹치면 맞은 것으로 본다(경계 차이와 진짜 누락을 나눠 보기 위함)
    for t in types:
        g = [k for k in gold_e if k[1] == t]
        p = [k for k in pred_e if k[1] == t]
        used, hit = set(), 0
        for gk in g:
            m = next(
                (
                    pk
                    for pk in p
                    if pk not in used
                    and pk[0] == gk[0]
                    and pk[2] < gk[3]
                    and gk[2] < pk[3]
                ),
                None,
            )
            if m is not None:
                used.add(m)
                hit += 1
        by_type[t]["partial_correct"] = hit
        by_type[t]["partial_precision"] = round(len(used) / len(p), 4) if p else None
        by_type[t]["partial_recall"] = round(hit / len(g), 4) if g else None
    both = set(gold_e) & set(pred_e)
    linkable = [k for k in both if gold_e[k]["resolution"]["status"] != "none"]
    res_wrong = [
        f"{k[0]} '{gold_e[k]['text']}': 제안 {_res(pred_e[k])} / 정답 {_res(gold_e[k])}"
        for k in linkable
        if _res(pred_e[k]) != _res(gold_e[k])
    ]
    judged = [k for k in both if k[1] in ("OBSERVATION", "ACTION")]
    times = [k for k in both if k[1] == "TIME"]
    report = {
        "entities_by_type": by_type,
        "resolution": {
            "compared": len(linkable),
            "correct": len(linkable) - len(res_wrong),
            "wrong": sorted(res_wrong),
        },
        "assertion": {
            "compared": len(judged),
            "correct": sum(
                pred_e[k]["assertion"] == gold_e[k]["assertion"] for k in judged
            ),
        },
        "time_normalized": {
            "compared": len(times),
            "correct": sum(
                pred_e[k].get("normalized") == gold_e[k].get("normalized")
                for k in times
            ),
        },
        "missed_examples": sorted(
            f"{k[0]} {k[1]} '{gold_e[k]['text']}'" for k in set(gold_e) - set(pred_e)
        )[:20],
        "extra_examples": sorted(
            f"{k[0]} {k[1]} '{pred_e[k]['text']}'" for k in set(pred_e) - set(gold_e)
        )[:20],
    }
    if data_root:
        report["equipment_linking_given_gold_mentions"] = equipment_oracle(
            gold, data_root
        )
    return report


def equipment_oracle(gold: list[dict], data_root: Path) -> dict:
    equipment = load_equipment(
        CsvSourceReader("mes", Path(data_root) / "mes")
        .read_rows("equipment")
        .fillna("")
        .to_dict("records")
    )
    rows = defaultdict(int)
    wrong = []
    for d in gold:
        for e in d["entities"]:
            if e["type"] != "EQUIPMENT":
                continue
            got = resolve(e["text"], equipment).model_dump(mode="json")
            status = e["resolution"]["status"]
            rows[status] += 1
            if _res({"resolution": got}) == _res(e):
                rows[f"{status}_correct"] += 1
            else:
                wrong.append(
                    f"'{e['text']}': 제안 {_res({'resolution': got})} / 정답 {_res(e)}"
                )
    total = sum(v for k, v in rows.items() if not k.endswith("_correct"))
    correct = sum(v for k, v in rows.items() if k.endswith("_correct"))
    return {"total": total, "correct": correct, "by_status": dict(rows), "wrong": wrong}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="메모 추출 ↔ 정답 비교")
    parser.add_argument("result", type=Path)
    parser.add_argument("gold", type=Path)
    parser.add_argument("data", type=Path, nargs="?")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    pred = json.loads(args.result.read_text(encoding="utf-8"))["extractions"]
    gold = json.loads((args.gold / "note_annotations.json").read_text(encoding="utf-8"))
    print(json.dumps(evaluate(pred, gold, args.data), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
