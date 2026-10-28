"""교대 인수인계 메모 60건 (S15P31C106-33). 우리 데이터셋의 실제 식별자(설비·작업지시·정비·로트)를 언급한다.

개체 위치는 문장을 만들면서 코드가 계산한다. 정비 지시(mes.maintenance_order.note)도 짧은 비정형 기록으로 주석한다.
"""

import random
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

DAY, NIGHT = (8, 17), (20, 5)  # 실적과 같은 교대 시각
# 설비 코드 → (정식 이름, 라인, 라인 안 호기 번호)
EQUIPMENT = {
    "B101": ("1라인 블랭킹기", "1라인", None),
    "P101": ("1라인 1호 프레스", "1라인", 1),
    "P103": ("1라인 3호 프레스", "1라인", 3),
    "I101": ("1라인 검사대", "1라인", None),
    "P201": ("2라인 1호 프레스", "2라인", 1),
    "W201": ("2라인 용접기", "2라인", None),
}
PRESSES = ["P101", "P103", "P201"]
NEG_AFTER = {
    "유압 펌프 필터 교체": "압력 저하 없음",
    "클러치 라이닝 교체": "소음 없음",
    "피더 센서 교체": "오작동 없음",
    "가이드 라이너 교체": "이음 없음",
    "월간 PM, 윤활유 교환": "이상 없음",
    "윤활 배관 점검·보충": "이상 없음",
}
N1_TEXTS = [
    ("진동이 평소보다 커서", "점검 요청", "함."),
    ("이음이 들려서", "가동 멈추고 확인", "함."),
    ("유압 압력이 떨어져서", "보전팀 호출", "함."),
    ("소재 걸림이 반복돼서", "금형 청소", "함."),
]
N2_TEXTS = [
    ("소음 계속됨", "금형 교체", " 후에도 비슷함."),
    ("진동 있음", "볼트 재체결", " 했는데 지켜봐야 함."),
]
N9_TEXTS = ["진동 이상 없음", "누유 없음", "소음 없음"]
N10_TEXTS = [
    ("소음", "은 ", "금형 문제", "인 듯."),
    ("진동", "은 ", "베어링 마모", " 때문인 것 같음."),
]
N8_TEXTS = ["특이사항 없음.", "전달 사항 없음.", "이상 없이 교대함."]


@dataclass(frozen=True)
class Mention:
    text: str
    status: str  # resolved · ambiguous · unmatched
    ref: str | None = None
    candidates: tuple[str, ...] = ()


@dataclass
class Doc:
    doc_id: str
    source: str
    meta: dict
    text: str = ""
    patterns: list[str] = field(default_factory=list)
    entities: list[dict] = field(default_factory=list)
    relations: list[dict] = field(default_factory=list)
    review: list[str] = field(default_factory=list)

    def lit(self, s: str) -> None:
        self.text += s

    def ent(
        self,
        etype,
        s,
        assertion="affirmed",
        status="none",
        ref=None,
        candidates=(),
        normalized=None,
    ) -> str:
        eid = f"e{len(self.entities) + 1}"
        start = len(self.text)
        self.text += s
        self.entities.append(
            {
                "id": eid,
                "type": etype,
                "start": start,
                "end": len(self.text),
                "text": s,
                "assertion": assertion,
                "resolution": {
                    "status": status,
                    "ref": ref,
                    "candidates": list(candidates),
                },
                "normalized": normalized,
            }
        )
        return eid

    def equip(self, m: Mention) -> str:
        eid = self.ent(
            "EQUIPMENT", m.text, status=m.status, ref=m.ref, candidates=m.candidates
        )
        self.rel("mentions_equipment", "doc", eid)
        return eid

    def rel(self, rtype: str, a: str, b: str) -> None:
        self.relations.append({"type": rtype, "from": a, "to": b})

    def tag(self, pattern: str) -> None:
        if pattern not in self.patterns:
            self.patterns.append(pattern)

    def to_gold(self) -> dict:
        return {
            "doc_id": self.doc_id,
            "source": self.source,
            "pattern": self.patterns,
            "text": self.text,
            "meta": self.meta,
            "entities": self.entities,
            "relations": self.relations,
            "review_note": " ".join(self.review) or None,
        }


def _ref(code: str) -> str:
    return f"mes.equipment:{code}"


def mention(code: str, style: str) -> Mention:
    name, line, number = EQUIPMENT[code]
    if style == "formal" or number is None:
        return Mention(name, "resolved", _ref(code))
    if (
        style == "short"
    ):  # 'N호기'. 라인 표시가 없어 같은 번호 설비가 여럿이면 모호(1호기 = 1라인·2라인 1호 프레스)
        same = [c for c, (_, _, n) in EQUIPMENT.items() if n == number]
        return Mention(
            f"{number}호기",
            "resolved" if len(same) == 1 else "ambiguous",
            _ref(code) if len(same) == 1 else None,
            tuple(_ref(c) for c in same) if len(same) > 1 else (),
        )
    if style == "code":
        return Mention(code, "resolved", _ref(code))
    if style == "loose":
        return Mention(code.lower(), "resolved", _ref(code))
    if style == "dash":
        return Mention(f"{line[0]}-{number}호", "resolved", _ref(code))
    return Mention(f"{line} {number}호", "resolved", _ref(code))


@dataclass
class Ctx:
    rng: random.Random
    day: date
    shift: str  # 주간 · 야간
    author: dict
    rows: dict


def _new_note(day: date, shift: str, author: dict) -> Doc:
    h1, h2 = DAY if shift == "주간" else NIGHT
    start = datetime.combine(day, datetime.min.time()) + timedelta(hours=h1)
    end = start + timedelta(hours=(h2 - h1) % 24)
    nxt = "야간" if shift == "주간" else "주간"
    doc = Doc(
        f"HN-{day:%y%m%d}-{'D' if shift == '주간' else 'N'}",
        "note",
        {
            "shift": shift,
            "period": {"start": start.isoformat(), "end": end.isoformat()},
            "author": f"mes.worker:{author['id']}",
        },
    )
    hand_over_day = day if shift == "주간" else day + timedelta(days=1)
    doc.lit(
        f"[{day.month}/{day.day} {shift} → {hand_over_day.month}/{hand_over_day.day} {nxt} 인수인계] 작성 {author['id']}\n"
    )
    return doc


def _time(doc, ctx, phrase=None):
    phrases = (
        [
            ("새벽 2시쯤", 1, 2),
            ("새벽 3시쯤", 1, 3),
            ("밤 10시쯤", 0, 22),
            ("교대 직후", 0, 20),
        ]
        if ctx.shift == "야간"
        else [
            ("오전 10시쯤", 0, 10),
            ("점심 직후", 0, 13),
            ("오후 3시쯤", 0, 15),
            ("교대 직후", 0, 8),
        ]
    )
    text, offset, hour = phrase or ctx.rng.choice(phrases)
    moment = datetime.combine(
        ctx.day + timedelta(days=offset), datetime.min.time()
    ) + timedelta(hours=hour)
    if offset:
        doc.tag("T4")
    return doc.ent("TIME", text, normalized=moment.isoformat())


def n1(doc, ctx, code=None, phrase=None, texts=None, style=None):
    code = code or ctx.rng.choice(PRESSES + ["B101"])
    obs, act, tail = texts or ctx.rng.choice(N1_TEXTS)
    _time(doc, ctx, phrase)
    doc.lit(" ")
    e = doc.equip(
        mention(code, style or ctx.rng.choice(["formal", "formal", "code", "line"]))
    )
    doc.lit(" ")
    o = doc.ent("OBSERVATION", obs)
    doc.lit(" ")
    doc.ent("ACTION", act)
    doc.lit(tail + "\n")
    doc.rel("observed_on", o, e)
    doc.tag("N1")


def n2(doc, ctx):
    code = ctx.rng.choice(["P101", "P201"])
    obs, act, tail = ctx.rng.choice(N2_TEXTS)
    m = mention(code, "short")
    e = doc.equip(m)
    doc.lit(" ")
    o = doc.ent("OBSERVATION", obs)
    doc.lit(". ")
    doc.ent("ACTION", act)
    doc.lit(tail + "\n")
    doc.rel("observed_on", o, e)
    doc.review.append(
        f"'{m.text}' 표기는 1라인·2라인 1호 프레스 중 하나라 확정할 수 없어 보류로 둠. "
        f"작성자 배치 라인({ctx.author['line']})이 단서다."
    )
    doc.tag("N2")


def _active_wo(ctx):
    day = ctx.day.isoformat()
    running = [
        w
        for w in ctx.rows["mes.work_order"]
        if w["actual_start"]
        and w["actual_start"][:10] <= day
        and (w["actual_end"] or "9999")[:10] >= day
    ]
    return ctx.rng.choice(running) if running else None


def n3(doc, ctx, wo=None, remaining=None):
    wo = wo or _active_wo(ctx)
    if wo is None:
        return n9(doc, ctx)
    remaining = (
        remaining
        if remaining is not None
        else ctx.rng.randrange(100, max(200, int(wo["planned_qty"]) // 2), 100)
    )
    e = doc.ent(
        "WORK_ORDER",
        wo["wo_no"],
        status="resolved",
        ref=f"mes.work_order:{wo['wo_no']}",
    )
    doc.lit(f" 잔량 {remaining:,}개는 ")
    doc.ent("ACTION", "주간조에 넘김" if ctx.shift == "야간" else "야간조에 넘김")
    doc.lit(".\n")
    doc.rel("about_work_order", "doc", e)
    doc.tag("N3")


def n4(doc, ctx, mo=None):
    done = [
        m
        for m in ctx.rows["mes.maintenance_order"]
        if m["status"] == "완료"
        and m["target_type"] == "설비"
        and m["completed_at"]
        and m["completed_at"][:10] < ctx.day.isoformat()
        and m["note"] in NEG_AFTER
    ]
    mo = mo or (
        ctx.rng.choice(sorted(done, key=lambda m: m["completed_at"])[-5:])
        if done
        else None
    )
    if mo is None:
        return n9(doc, ctx)
    m = doc.ent(
        "MAINTENANCE",
        mo["mo_no"],
        status="resolved",
        ref=f"mes.maintenance_order:{mo['mo_no']}",
    )
    doc.lit(" ")
    doc.ent("ACTION", mo["note"])
    doc.lit(" 이후 ")
    e = doc.equip(mention(mo["target_code"], ctx.rng.choice(["formal", "code"])))
    doc.lit(" ")
    o = doc.ent("OBSERVATION", NEG_AFTER[mo["note"]], assertion="negated")
    doc.lit(".\n")
    doc.rel("refers_maintenance", "doc", m)
    doc.rel("observed_on", o, e)
    doc.tag("N4")


def n5(doc, ctx, code=None, obs=None, style=None):
    code = code or ctx.rng.choice(PRESSES)
    e = doc.equip(mention(code, style or ctx.rng.choice(["loose", "dash", "code"])))
    doc.lit(" ")
    o = doc.ent(
        "OBSERVATION", obs or ctx.rng.choice(["진동↑", "온도 경고 2회", "이음 발생"])
    )
    doc.lit(".\n")
    doc.rel("observed_on", o, e)
    doc.tag("N5")


def n7(doc, ctx, index=0):
    text, reason = [
        ("1라인 2호기", "1라인에 2호 프레스가 없음"),
        ("2라인 3호기", "2라인에 3호 프레스가 없음"),
    ][index % 2]
    e = doc.equip(Mention(text, "unmatched"))
    doc.lit(" 쪽에서 ")
    o = doc.ent("OBSERVATION", "이상한 소리")
    doc.lit(" 남. ")
    doc.ent("ACTION", "확인 바람")
    doc.lit(".\n")
    doc.rel("observed_on", o, e)
    doc.review.append(
        f"'{text}' 표기는 없는 설비다({reason}). 연결하지 않는 것이 정답."
    )
    doc.tag("N7")


def n8(doc, ctx):
    doc.lit(ctx.rng.choice(N8_TEXTS) + "\n")
    doc.tag("N8")


def n9(doc, ctx, code=None):
    code = code or ctx.rng.choice(PRESSES)
    e = doc.equip(mention(code, ctx.rng.choice(["formal", "code"])))
    doc.lit(" ")
    o = doc.ent("OBSERVATION", ctx.rng.choice(N9_TEXTS), assertion="negated")
    doc.lit(".\n")
    doc.rel("observed_on", o, e)
    doc.review.append(f"{o}: 부정 표현. 이상이 있다고 바꾸면 오답.")
    doc.tag("N9")


def n10(doc, ctx, code=None):
    code = code or ctx.rng.choice(["P201", "P101"])
    symptom, josa, cause, tail = ctx.rng.choice(N10_TEXTS)
    e = doc.equip(mention(code, "line"))
    doc.lit(" ")
    o1 = doc.ent("OBSERVATION", symptom)
    doc.lit(josa)
    o2 = doc.ent("OBSERVATION", cause, assertion="uncertain")
    doc.lit(tail + "\n")
    doc.rel("observed_on", o1, e)
    doc.rel("observed_on", o2, e)
    doc.review.append(f"{o2}: 추정 표현. 확정 원인으로 다루면 오답.")
    doc.tag("N10")


def n11(doc, ctx, shipment=None):
    """로트 번호 언급."""
    lots = [
        s for s in ctx.rows["erp.shipment"] if s["ship_date"] >= ctx.day.isoformat()
    ]
    shipment = shipment or (ctx.rng.choice(lots[:10]) if lots else None)
    if shipment is None:
        return n8(doc, ctx)
    e = doc.ent(
        "LOT",
        shipment["lot_no"],
        status="resolved",
        ref=f"erp.shipment:{shipment['ship_no']}",
    )
    doc.lit(" 출하 전 ")
    doc.ent("ACTION", "외관 확인")
    doc.lit(" 완료.\n")
    doc.rel("about_lot", "doc", e)
    doc.tag("N11")


SENTENCES = {
    "N1": n1,
    "N2": n2,
    "N3": n3,
    "N4": n4,
    "N5": n5,
    "N9": n9,
    "N10": n10,
    "N11": n11,
}
REQUIRED = {"N1": 7, "N2": 3, "N3": 7, "N4": 3, "N5": 5, "N9": 5, "N10": 3, "N11": 3}


def build_notes(rows: dict, seed: int, start: date, end: date) -> list[Doc]:
    from app.dataset.build import MO_BREAKDOWN, WO_A, WO_B

    rng = random.Random(seed)
    workers = {w["id"]: w for w in rows["mes.worker"]}
    by_shift = {
        s: [w for w in workers.values() if w["shift"] == s] for s in ("주간", "야간")
    }
    night_line1 = next(
        w
        for w in workers.values()
        if w["shift"] == "야간" and w["line"] == "1라인" and w["id"] != "57"
    )
    wos = {w["wo_no"]: w for w in rows["mes.work_order"]}
    mos = {m["mo_no"]: m for m in rows["mes.maintenance_order"]}
    done = sorted(
        (
            m
            for m in mos.values()
            if m["mo_type"] == "고장"
            and m["status"] == "완료"
            and m["note"] in NEG_AFTER
        ),
        key=lambda m: m["completed_at"],
    )
    late_lot = next(s for s in rows["erp.shipment"] if s["ship_date"] >= "2026-10-24")

    def ctx(day, shift, author):
        return Ctx(rng, day, shift, author, rows)

    fixed = [  # P103 고장 이야기(시나리오 4)
        (
            date(2026, 10, 27),
            "야간",
            night_line1["id"],
            lambda d, c: n1(d, c, "P103", ("새벽 2시쯤", 1, 2), N1_TEXTS[0], "formal"),
        ),
        (
            date(2026, 10, 28),
            "주간",
            "31",
            lambda d, c: n5(d, c, "P103", "진동 상한 넘음", "code"),
        ),
        (
            date(2026, 10, 29),
            "주간",
            "31",
            lambda d, c: (n9(d, c, "P101"), n4(d, c, done[-1])),
        ),
        (
            date(2026, 10, 29),
            "야간",
            "57",
            lambda d, c: (
                _breakdown(d, c, mos[MO_BREAKDOWN]),
                _stopped(d, c, wos[WO_A]),
                n3(d, c, wos[WO_B], 2400),
            ),
        ),
        (date(2026, 10, 24), "주간", "31", lambda d, c: n11(d, c, late_lot)),
    ]
    notes, taken = [], set()
    for day, shift, author_id, write in fixed:
        doc = _new_note(day, shift, workers[author_id])
        write(doc, ctx(day, shift, workers[author_id]))
        notes.append(doc)
        taken.add((day, shift))

    slots = [
        (start + timedelta(days=i), s)
        for i in range((end - start).days + 1)
        for s in ("주간", "야간")
    ]
    slots = sorted(rng.sample([s for s in slots if s not in taken], 60 - len(fixed)))
    empty = set(rng.sample(slots, 12))
    busy = [s for s in slots if s not in empty]
    pool = [p for p, n in REQUIRED.items() for _ in range(n)] + ["N7", "N7"]
    pool += rng.choices(["N1", "N3", "N5", "N9"], k=max(0, len(busy) + 8 - len(pool)))
    rng.shuffle(pool)
    assignment = [[p] for p in pool[: len(busy)]]
    for p in pool[len(busy) :]:
        rng.choice([a for a in assignment if len(a) < 3]).append(p)
    n7_count = 0
    for (day, shift), patterns in zip(busy, assignment):
        author = rng.choice(by_shift[shift])
        doc = _new_note(day, shift, author)
        c = ctx(day, shift, author)
        for p in patterns:
            if p == "N7":
                n7(doc, c, n7_count)
                n7_count += 1
            else:
                SENTENCES[p](doc, c)
        notes.append(doc)
    for day, shift in sorted(empty):
        author = rng.choice(by_shift[shift])
        doc = _new_note(day, shift, author)
        n8(doc, ctx(day, shift, author))
        notes.append(doc)
    notes.sort(key=lambda d: d.meta["period"]["start"])
    return notes


def _breakdown(doc, ctx, mo):
    _time(doc, ctx, ("새벽 3시쯤", 1, 3))
    doc.lit(" ")
    e = doc.equip(mention("P103", "short"))
    doc.lit(" ")
    o = doc.ent("OBSERVATION", "슬라이드 유압 누유")
    doc.lit("로 정지. ")
    m = doc.ent(
        "MAINTENANCE",
        mo["mo_no"],
        status="resolved",
        ref=f"mes.maintenance_order:{mo['mo_no']}",
    )
    doc.lit(" ")
    doc.ent("ACTION", "정비 진행 중")
    doc.lit(".\n")
    doc.rel("observed_on", o, e)
    doc.rel("refers_maintenance", "doc", m)
    doc.tag("N1")
    doc.tag("N4")


def _stopped(doc, ctx, wo):
    w = doc.ent(
        "WORK_ORDER",
        wo["wo_no"],
        status="resolved",
        ref=f"mes.work_order:{wo['wo_no']}",
    )
    doc.lit(" 성형은 ")
    e = doc.equip(mention("P103", "code"))
    doc.lit(" ")
    o = doc.ent("OBSERVATION", "정지로 중단")
    remaining = int(wo["planned_qty"]) - int(wo["produced_qty"])
    doc.lit(f", 잔량 {remaining:,}개.\n")
    doc.rel("about_work_order", "doc", w)
    doc.rel("observed_on", o, e)
    doc.tag("N3")


def maintenance_docs(rows: dict) -> list[Doc]:
    """정비 지시의 note 칸(짧은 비정형 기록)을 조치로 주석한다."""
    docs = []
    for m in rows["mes.maintenance_order"]:
        if not m["note"]:
            continue
        doc = Doc(
            m["mo_no"],
            "maint",
            {
                "shift": None,
                "period": {"start": m["planned_date"], "end": m["planned_date"]},
                "author": m["technician"],
            },
        )
        doc.ent("ACTION", m["note"])
        docs.append(doc)
    return docs
