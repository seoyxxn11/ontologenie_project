"""00프레스(가상) 3개월 원천 데이터 생성기.

    .\\.venv\\Scripts\\python.exe -m app.dataset.build --version v1.0

출력: data/data-<버전>/(AI 입력: erp·mes 표, 메모), data/gold-<버전>/(정답), data/scenario-<버전>/(S5 원천 수정분).
기업 예시는 표 구성만 참고했고 값은 모두 새로 만들었다. 같은 시드면 항상 같은 결과가 나온다.
"""

import argparse
import csv
import hashlib
import json
import math
import random
import shutil
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path

from app.dataset import schema
from app.dataset.checks import EXPECTED, run_checks

DATA_ROOT = Path(__file__).resolve().parents[2] / "data"
NOW = datetime(2026, 10, 30, 14, 0)  # 데이터를 내려받은 시각. P103 고장 정비 진행 중
HISTORY_START, HISTORY_END = date(2026, 8, 3), date(2026, 10, 25)
SHIFTS = {"주간": (8, 17), "야간": (20, 5)}

CUSTOMERS = [("CU0001", "한결모터스"), ("CU0002", "새벽오토"), ("CU0003", "다온차체")]
SUPPLIERS = [("SP0001", "대한강판"), ("SP0002", "성진볼트"), ("SP0003", "미래코일")]
ITEMS = [  # 품번, 품명, 규격, 단위, 구분, 단가, 안전재고
    ("BRK-210L", "프론트 브래킷 좌", "프레스 성형품", "EA", "FG", 4300, 300),
    ("BRK-210R", "프론트 브래킷 우", "프레스 성형품", "EA", "FG", 4300, 300),
    ("RNF-320L", "사이드 보강재 좌", "프레스 성형품", "EA", "FG", 5600, 200),
    ("RNF-320R", "사이드 보강재 우", "프레스 성형품", "EA", "FG", 5600, 200),
    ("PNL-410", "휠하우스 패널", "프레스 성형품", "EA", "FG", 2900, 400),
    ("BLK-210", "브래킷 블랭크", "블랭킹 반제품", "EA", "SF", 0, 0),
    ("BLK-320", "보강재 블랭크", "블랭킹 반제품", "EA", "SF", 0, 0),
    ("CL-440", "냉연 코일 SPH440", "1.2t x 1250", "KG", "RM", 1250, 8000),
    ("CL-590", "고장력 코일 SGC590", "2.0t x 1250", "KG", "RM", 1480, 5000),
    ("BT-M8", "육각 볼트 M8", "M8 x 20", "EA", "RM", 90, 3000),
]
PROTOTYPE = "BRK-990T"  # 금형에만 있는 시제품. 품목 마스터에 없다(검사 K4)
COIL = {
    "BRK-210L": "CL-440",
    "BRK-210R": "CL-440",
    "RNF-320L": "CL-590",
    "RNF-320R": "CL-590",
    "PNL-410": "CL-440",
}
KG_PER_PIECE = {
    "BRK-210L": 0.6,
    "BRK-210R": 0.6,
    "RNF-320L": 1.4,
    "RNF-320R": 1.4,
    "PNL-410": 0.9,
}
EQUIPMENT = [  # 코드, 이름, 종류, 라인, 톤수, 설치일, 제조사, 자산 이름, 취득가, 장부가
    (
        "B101",
        "1라인 블랭킹기",
        "BLK",
        "1라인",
        250,
        "2017-04-10",
        "한국프레스",
        "블랭킹 프레스 250톤",
        360_000_000,
        128_000_000,
    ),
    (
        "P101",
        "1라인 1호 프레스",
        "PRS",
        "1라인",
        400,
        "2017-04-10",
        "한국프레스",
        "프레스 400톤 (1라인 1호)",
        790_000_000,
        268_000_000,
    ),
    (
        "P103",
        "1라인 3호 프레스",
        "PRS",
        "1라인",
        600,
        "2022-02-21",
        "대성기계",
        "프레스 600톤 (1라인 3호)",
        1_240_000_000,
        830_000_000,
    ),
    (
        "I101",
        "1라인 검사대",
        "INS",
        "1라인",
        None,
        "2023-06-12",
        "자체 제작",
        "3차원 검사대",
        92_000_000,
        64_000_000,
    ),
    (
        "P201",
        "2라인 1호 프레스",
        "PRS",
        "2라인",
        300,
        "2015-09-07",
        "한국프레스",
        "프레스 300톤 (2라인 1호)",
        540_000_000,
        84_000_000,
    ),
    (
        "W201",
        "2라인 용접기",
        "WLD",
        "2라인",
        None,
        "2021-05-17",
        "세원로보틱스",
        "스폿 용접기",
        115_000_000,
        61_000_000,
    ),
]
PRESS_TONNAGE = {e[0]: e[4] for e in EQUIPMENT if e[2] == "PRS"}
LINE_OF = {e[0]: e[3] for e in EQUIPMENT}
ROUTING = {  # 제품: [(공정, 공정명, 설비 종류, 최소 톤수, 사이클 초, 준비 분)]
    "BRK": [
        (10, "블랭킹", "BLK", 200, 3.0, 15),
        (20, "성형", "PRS", 400, 6.0, 40),
        (30, "트리밍", "PRS", 400, 5.5, 30),
        (40, "검사", "INS", None, 3.5, 5),
    ],
    "RNF": [
        (10, "블랭킹", "BLK", 200, 3.4, 15),
        (20, "성형", "PRS", 400, 7.0, 50),
        (30, "피어싱", "PRS", 400, 5.0, 30),
        (40, "검사", "INS", None, 3.5, 5),
    ],
    "PNL": [
        (10, "블랭킹", "BLK", 200, 2.8, 15),
        (20, "성형", "PRS", 300, 4.5, 35),
        (30, "트리밍", "PRS", 300, 4.0, 25),
        (40, "볼트 용접", "WLD", None, 8.0, 20),
        (50, "검사", "INS", None, 3.0, 5),
    ],
}
SF_OF = {"BRK": "BLK-210", "RNF": "BLK-320", "PNL": None}
FIXED_EQUIPMENT = {"BLK": "B101", "INS": "I101", "WLD": "W201"}
BOLTS_PER_PANEL = 2  # 휠하우스 패널 하나에 용접 볼트 M8 두 개
DEPARTMENTS = [
    ("DP10", "생산관리팀", ""),
    ("DP20", "프레스1팀", "DP10"),
    ("DP21", "프레스2팀", "DP10"),
    ("DP30", "품질팀", ""),
    ("DP40", "보전팀", "DP10"),
    ("DP50", "영업팀", ""),
    ("DP60", "구매팀", ""),
    ("DP70", "정보시스템팀", ""),
]
SURNAMES = list("김이박최정강조윤장임한오서신권황안송류홍")
GIVEN = [
    "민준",
    "서준",
    "도윤",
    "지호",
    "현우",
    "승민",
    "지훈",
    "준혁",
    "성민",
    "재원",
    "동현",
    "은지",
    "수빈",
    "하은",
    "지수",
    "유진",
    "예린",
    "민서",
    "다은",
    "태양",
    "가은",
    "나래",
    "보람",
    "슬기",
]

# 시나리오 4: P103 고장으로 멈춘 작업지시 A, P101에서 진행 중인 B, 대기라 납기가 위험한 C
WO_A, WO_B, WO_C = "JB2610-0141", "JB2610-0138", "JB2610-0145"
SO_MAIN, SO_RISK = "OD-2610-021", "OD-2610-019"
MO_BREAKDOWN, DT_BREAKDOWN = "RP-26-041", "DT-0460"
BREAKDOWN_AT = datetime(2026, 10, 30, 3, 10)


def fmt(moment) -> str:
    if moment is None or moment == "":
        return ""
    return (
        moment.strftime("%Y-%m-%d %H:%M:%S")
        if isinstance(moment, datetime)
        else moment.isoformat()
    )


def compact(so_no: str) -> str:
    """MES 수주 참조 표기. OD-2610-021 → 2610021"""
    return so_no[3:7] + so_no[8:]


def shift_windows(t: datetime):
    """t 이후에 끝나는 교대를 차례로 (교대, 시작, 끝)으로 낸다. 일요일과 토요일 야간은 쉰다."""
    day = t.date() - timedelta(days=1)
    while True:
        base = datetime.combine(day, datetime.min.time())
        for shift, (h1, h2) in SHIFTS.items():
            start = base + timedelta(hours=h1)
            end = start + timedelta(hours=(h2 - h1) % 24)
            resting = day.weekday() == 6 or (day.weekday() == 5 and shift == "야간")
            if end > t and not resting:
                yield shift, start, end
        day += timedelta(days=1)


def shift_of(moment: datetime) -> tuple[str, date]:
    """시각이 속한 교대와 그 교대가 시작한 날짜. 야간 새벽 시간은 전날 야간이다."""
    if moment.hour < 8:
        return "야간", moment.date() - timedelta(days=1)
    return ("주간" if moment.hour < 18 else "야간"), moment.date()


class Calendar:
    """설비별 작업 시간표. 같은 설비에 작업이 겹치지 않고, 비가동 구간에는 작업하지 않는다."""

    def __init__(self) -> None:
        self.free: dict[str, datetime] = {}
        self.blocked: dict[str, list[tuple[datetime, datetime]]] = defaultdict(list)

    def free_at(self, equipment: str) -> datetime:
        return self.free.get(
            equipment, datetime.combine(HISTORY_START, datetime.min.time())
        )

    def book(self, equipment: str, earliest: datetime, minutes: int) -> list[tuple]:
        """작업 시간(분)을 교대 안에 채운다. 교대마다 (교대, 시작, 끝, 실제 작업 분)."""
        t = max(earliest, self.free_at(equipment))
        left, segments = minutes, []
        for shift, ws, we in shift_windows(t):
            pieces, cur = [], max(t, ws)
            for bs, be in sorted(self.blocked[equipment]):
                if be <= cur or bs >= we:
                    continue
                if bs > cur:
                    pieces.append((cur, bs))
                cur = max(cur, be)
            if cur < we:
                pieces.append((cur, we))
            first = last = None
            worked = 0
            for ps, pe in pieces:
                take = min(left, int((pe - ps).total_seconds() // 60))
                if take <= 0:
                    continue
                first = first or ps
                last = ps + timedelta(minutes=take)
                worked += take
                left -= take
                if left == 0:
                    break
            if worked:
                segments.append((shift, first, last, worked))
            if left == 0:
                break
        self.free[equipment] = segments[-1][2]
        return segments


class Builder:
    def __init__(self, seed: int):
        self.rng = random.Random(seed)
        self.time_rng = random.Random(seed + 1)  # 작업 효율(실제 시간)
        self.extra_rng = random.Random(seed + 2)  # 센서·근태
        self.cal = Calendar()
        self.rows: dict[str, list[dict]] = defaultdict(list)
        self.cases: dict[str, str] = {}  # 정합성 검사 9건이 볼 대상
        self.planted: list[dict] = []
        self.used: dict[str, set] = defaultdict(set)

    def add(self, key: str, **values) -> dict:
        table = schema.TABLE_BY_KEY[key]
        row = {
            name: fmt(values.get(name, ""))
            if isinstance(values.get(name), (date, datetime))
            else ("" if values.get(name) is None else str(values.get(name, "")))
            for name in table.column_names
        }
        unknown = set(values) - set(table.column_names)
        if unknown:
            raise KeyError(f"{key}에 없는 컬럼: {unknown}")
        self.rows[key].append(row)
        return row

    def plant(self, case_id, description, location, rows=(), values=None):
        self.planted.append(
            {
                "case_id": case_id,
                "description": description,
                "location": location,
                "rows": list(rows),
                "values": values,
            }
        )

    def number(self, kind: str, fmt_str: str, start: int = 1) -> str:
        n = start
        while fmt_str.format(n) in self.used[kind]:
            n += 1
        value = fmt_str.format(n)
        self.used[kind].add(value)
        return value

    def name(self) -> str:
        while True:
            value = self.rng.choice(SURNAMES) + self.rng.choice(GIVEN)
            if value not in self.used["name"]:
                self.used["name"].add(value)
                return value

    # ---------- 실행 ----------
    def build(self) -> None:
        for key in (WO_A, WO_B, WO_C):
            self.used["wo"].add(key)
        for key in (SO_MAIN, SO_RISK):
            self.used["so"].add(key)
        self.used["mo"].add(MO_BREAKDOWN)
        self.used["dt"].add(DT_BREAKDOWN)
        self._masters()
        self._people()
        self._plan_downtimes()
        self._history()
        self._scenario()
        self._record_downtimes()
        self._sensors()
        self._purchasing()
        self._stock()
        self._accounting()
        self._attendance()
        self._plant_integrity_cases()

    # ---------- 기준 정보 ----------
    def _masters(self) -> None:
        for code, name in CUSTOMERS:
            self.add(
                "erp.vendor",
                vendor_code=code,
                vendor_name=name,
                biz_no=f"{self.rng.randint(100, 899)}-{self.rng.randint(10, 99)}-*****",
                vendor_type="S",
                contact_name=self.name(),
                payment_terms=self.rng.choice(
                    ["월말 현금", "익월 말 현금", "60일 어음"]
                ),
                use_yn="Y",
            )
        for code, name in SUPPLIERS:
            self.add(
                "erp.vendor",
                vendor_code=code,
                vendor_name=name,
                biz_no=f"{self.rng.randint(100, 899)}-{self.rng.randint(10, 99)}-*****",
                vendor_type="P",
                contact_name=self.name(),
                payment_terms=self.rng.choice(["월말 현금", "익월 25일"]),
                use_yn="Y",
            )
        for code, name, spec, unit, kind, price, safety in ITEMS:
            self.add(
                "erp.item",
                item_code=code,
                item_name=name,
                spec=spec,
                unit=unit,
                item_type=kind,
                std_price=f"{price:.2f}",
                safety_stock=safety,
                use_yn="Y",
                updated_at=datetime(2026, 7, 20, 9, 0),
            )
        for code, name, kind, line, ton, installed, maker, *_ in EQUIPMENT:
            self.add(
                "mes.equipment",
                code=code,
                name=name,
                kind=kind,
                line=line,
                capacity_ton=ton,
                status="정비중" if code == "P103" else "가동",
                installed_on=date.fromisoformat(installed),
                maker=maker,
            )
        for product in [i[0] for i in ITEMS if i[4] == "FG"] + [PROTOTYPE]:
            for seq, op, kind, ton, cycle, setup in ROUTING[product[:3]]:
                if product == PROTOTYPE:
                    break
                self.add(
                    "mes.routing",
                    product_code=product,
                    op_seq=seq,
                    op_name=op,
                    equipment_kind=kind,
                    std_cycle_sec=f"{cycle:.2f}",
                    std_setup_min=setup,
                    min_tonnage=ton,
                )
        dies = [
            (f"MD-{p.replace('-', '')}-01", f"DWG-{p}", p)
            for p in [i[0] for i in ITEMS if i[4] == "FG"]
        ]
        dies.append(("MD-990T-01", "DWG-BRK-990T", PROTOTYPE))
        for code, drawing, product in dies:
            self.add(
                "mes.die",
                die_code=code,
                drawing_code=drawing,
                product_code=product,
                total_strokes=self.rng.randrange(300_000, 1_500_000, 500),
                pm_interval_strokes=200_000,
                last_pm_at=date(2026, 9, self.rng.randint(1, 28)),
                status="정비" if product == PROTOTYPE else "사용",
                location="금형창고"
                if product == PROTOTYPE
                else f"{'2' if product == 'PNL-410' else '1'}라인 금형대",
            )
        self.cases["K4_die"], self.cases["K4_product"] = "MD-990T-01", PROTOTYPE

    def _people(self) -> None:
        rng = self.rng
        groups = [
            ("주간", "1라인"),
            ("야간", "1라인"),
            ("주간", "2라인"),
            ("야간", "2라인"),
        ]
        numbers = sorted(
            rng.sample([n for n in range(5, 80) if n not in (31, 44, 57)], 13)
            + [31, 44]
        )
        self.workers = []
        for i, n in enumerate(numbers):
            shift, line = (
                ("주간", "1라인")
                if n == 31
                else ("주간", "2라인")
                if n == 44
                else groups[i % 4]
            )
            self.workers.append(
                self.add(
                    "mes.worker",
                    id=n,
                    name=self.name(),
                    shift=shift,
                    line=line,
                    skill_level=rng.randint(1, 5),
                    active_yn="Y",
                )
            )
            self.add(
                "erp.employee",
                emp_no=f"GP{n:04d}",
                emp_name=self.workers[-1]["name"],
                dept_code="DP20" if line == "1라인" else "DP21",
                position=rng.choice(["사원", "주임", "반장"]),
                hire_date=date(rng.randint(2014, 2025), rng.randint(1, 12), 1),
                employment_type="정규",
                status="재직",
            )
        # 검사 K2: 작업자 57은 MES에만 있고 ERP 직원 표에 없다
        self.workers.append(
            self.add(
                "mes.worker",
                id=57,
                name=self.name(),
                shift="야간",
                line="1라인",
                skill_level=2,
                active_yn="Y",
            )
        )
        self.cases["K1_worker"], self.cases["K2_worker"] = "31", "57"
        office = []
        for no, dept, position in [
            (101, "DP10", "과장"),
            (102, "DP10", "대리"),
            (110, "DP20", "과장"),
            (111, "DP21", "과장"),
            (120, "DP30", "대리"),
            (130, "DP40", "주임"),
            (131, "DP40", "사원"),
            (140, "DP50", "대리"),
            (150, "DP60", "주임"),
            (160, "DP70", "사원"),
        ]:
            office.append(f"GP{no:04d}")
            self.add(
                "erp.employee",
                emp_no=f"GP{no:04d}",
                emp_name=self.name(),
                dept_code=dept,
                position=position,
                hire_date=date(rng.randint(2010, 2024), rng.randint(1, 12), 1),
                employment_type="정규",
                status="재직",
            )
        managers = {
            "DP10": "GP0101",
            "DP20": "GP0110",
            "DP21": "GP0111",
            "DP30": "GP0120",
            "DP40": "GP0130",
            "DP50": "GP0140",
            "DP60": "GP0150",
            "DP70": "GP0160",
        }
        for code, name, parent in DEPARTMENTS:
            self.add(
                "erp.department",
                dept_code=code,
                dept_name=name,
                parent_code=parent,
                manager_emp_no=managers[code],
            )
        assets = []
        for (
            code,
            name,
            kind,
            line,
            ton,
            installed,
            maker,
            asset_name,
            cost,
            book,
        ) in EQUIPMENT:
            mapped = {"P201": "P2O1"}.get(
                code, code
            )  # P201 자산에 숫자 0 대신 영문 O로 적은 오타
            assets.append(
                (asset_name, "기계장치", installed, cost, book, f"1공장 {line}", mapped)
            )
        assets += [
            (
                "지게차 2.5톤",
                "차량",
                "2020-03-02",
                36_000_000,
                12_000_000,
                "자재창고",
                "",
            ),
            (
                "브래킷 금형 세트",
                "공구",
                "2024-01-15",
                140_000_000,
                70_000_000,
                "금형창고",
                "",
            ),
        ]
        for i, (name, kind, acquired, cost, book, location, code) in enumerate(
            sorted(assets, key=lambda a: a[2]), 1
        ):
            self.add(
                "erp.fixed_asset",
                asset_no=f"FA-{i:04d}",
                asset_name=name,
                asset_type=kind,
                acquired_on=date.fromisoformat(acquired),
                acquisition_cost=f"{cost:.2f}",
                book_value=f"{book:.2f}",
                location=location,
                equipment_code=code,
            )
        by_code = {
            r["equipment_code"]: r["asset_no"] for r in self.rows["erp.fixed_asset"]
        }
        self.cases["K3_asset"] = by_code["P101"]
        self.plant(
            "M-ASSET-TYPO",
            "자산의 설비 코드 오타 P2O1 (실제 P201)",
            "erp.fixed_asset.equipment_code",
            [by_code["P2O1"]],
        )

    def worker(self, line: str, shift: str) -> dict:
        candidates = [
            w for w in self.workers if w["line"] == line and w["shift"] == shift
        ]
        return self.rng.choice(candidates or self.workers)

    # ---------- 3개월 이력: 수주 → 작업지시 → 공정 배정 → 실적·불량·자재 → 출하·검사 ----------
    def op_minutes(self, product: str, seq: int, qty: int) -> int:
        """준비 시간 + 수량 × 표준 사이클 × 실제 효율(표준보다 8~30% 느림)."""
        _, _, _, _, cycle, setup = next(
            op for op in ROUTING[product[:3]] if op[0] == seq
        )
        factor = self.time_rng.uniform(1.08, 1.3)
        return setup + math.ceil(qty * cycle * factor / 60)

    def schedule(self, product, qty, press, earliest) -> list[tuple]:
        """검사를 뺀 공정을 순서대로 설비 시간표에 넣는다. [(공정, 설비, 수량, 교대 구간)]"""
        ops, t = [], earliest
        for seq, _, kind, *_ in ROUTING[product[:3]]:
            if kind == "INS":
                continue
            equipment = FIXED_EQUIPMENT.get(kind, press)
            segments = self.cal.book(equipment, t, self.op_minutes(product, seq, qty))
            ops.append((seq, equipment, qty, segments))
            t = segments[-1][2]
        return ops

    def _history(self) -> None:
        rng = self.rng
        prices = {i[0]: i[5] for i in ITEMS}
        families = [("BRK-210L", "BRK-210R"), ("RNF-320L", "RNF-320R"), ("PNL-410",)]
        self.history_wos, self.shipments_for_inspection = [], []
        while True:
            family = rng.choices(families, [5, 3, 2])[0]
            products = (
                list(family)
                if len(family) == 1 or rng.random() < 0.7
                else [rng.choice(family)]
            )
            saved = dict(self.cal.free)
            plans = []
            for product in products:
                qty = rng.randrange(1000, 4501, 500)
                need = ROUTING[product[:3]][1][3]
                press = min(
                    (p for p, t in PRESS_TONNAGE.items() if t >= need),
                    key=lambda p: (self.cal.free_at(p), PRESS_TONNAGE[p]),
                )
                ops = self.schedule(product, qty, press, self.cal.free_at(press))
                plans.append((product, qty, press, ops))
                # 주문이 없어 프레스가 쉬는 시간
                self.cal.free[press] = self.cal.free_at(press) + timedelta(
                    hours=rng.choice([0, 6, 12])
                )
            if max(p[3][-1][3][-1][2] for p in plans).date() > HISTORY_END:
                self.cal.free = saved
                break
            so_date = min(p[3][0][3][0][1] for p in plans).date() - timedelta(
                days=rng.randint(3, 10)
            )
            so_no = self.number("so", f"OD-{so_date:%y%m}-{{:03d}}")
            customer = rng.choice(CUSTOMERS)[0]
            last_end = max(p[3][-1][3][-1][2] for p in plans).date()
            self.add(
                "erp.sales_order",
                so_no=so_no,
                customer_code=customer,
                so_date=so_date,
                due_date=last_end + timedelta(days=rng.randint(2, 7)),
                status="출하완료",
                total_amount=f"{sum(prices[p] * q for p, q, *_ in plans):.2f}",
                sales_rep="영업팀",
            )
            for line_no, (product, qty, press, ops) in enumerate(plans, 1):
                self.add(
                    "erp.sales_order_line",
                    so_no=so_no,
                    line_no=line_no,
                    item_code=product,
                    qty=qty,
                    unit_price=f"{prices[product]:.2f}",
                    shipped_qty=qty,
                )
                wo = self._work_order(so_no, product, qty, press, ops)
                self._ship(so_no, product, qty, ops[-1][3][-1][2], wo)
        for so_date in (date(2026, 8, 18), date(2026, 9, 22)):  # 취소 수주
            so_no = self.number("so", f"OD-{so_date:%y%m}-{{:03d}}")
            self.add(
                "erp.sales_order",
                so_no=so_no,
                customer_code="CU0003",
                so_date=so_date,
                due_date=so_date + timedelta(days=20),
                status="취소",
                total_amount=f"{2000 * 4300:.2f}",
                sales_rep="영업팀",
            )
            self.add(
                "erp.sales_order_line",
                so_no=so_no,
                line_no=1,
                item_code="BRK-210L",
                qty=2000,
                unit_price="4300.00",
                shipped_qty=0,
            )

    def _work_order(
        self, so_no, product, qty, press, ops, status="완료", wo_no=None, planned=None
    ) -> dict:
        """작업지시 한 건과 그 실적·불량·자재 투입. ops는 schedule() 결과(진행 중이면 한 일까지만)."""
        rng = self.rng
        first = ops[0][3][0][1]
        last = ops[-1][3][-1][2]
        planned_start, planned_end = planned or (first.date(), last.date())
        wo_no = wo_no or self.number(
            "wo", f"JB{planned_start:%y%m}-{{:04d}}", start=101
        )
        wo = self.add(
            "mes.work_order",
            wo_no=wo_no,
            product_code=product,
            die_drawing_code=f"DWG-{product}",
            status=status,
            priority=rng.choice(["보통", "보통", "긴급", "낮음"]),
            line=LINE_OF[press],
            planned_qty=qty,
            produced_qty=0,
            defect_qty=0,
            planned_start=planned_start,
            planned_end=planned_end,
            actual_start=first,
            actual_end=last if status == "완료" else "",
            so_ref=compact(so_no),
        )
        defect_total = 0
        last_seq = max(op[0] for op in ROUTING[product[:3]] if op[2] != "INS")
        for seq, equipment, done, segments in ops:
            good, ng = self._results(wo_no, seq, equipment, done, segments)
            defect_total += ng
            if seq == last_seq:
                wo["produced_qty"] = str(good)
            if ng:
                shift, s, e, _ = segments[0]
                self.add(
                    "mes.defect",
                    defect_id=self.number("defect", "{}", start=5001),
                    wo_no=wo_no,
                    op_seq=seq,
                    equipment_code=equipment,
                    defect_type=rng.choice(["크랙", "주름", "버", "치수", "스크래치"]),
                    qty=ng,
                    disposition=rng.choice(["폐기", "재작업"]),
                    detected_at=min(s + timedelta(hours=2), e),
                    detected_by=self.worker(LINE_OF[equipment], shift)["id"],
                )
            kind = next(op[2] for op in ROUTING[product[:3]] if op[0] == seq)
            if kind in ("BLK", "WLD"):
                item = COIL[product] if kind == "BLK" else "BT-M8"
                amount = (
                    f"{done * KG_PER_PIECE[product]:.2f}"
                    if kind == "BLK"
                    else f"{done * BOLTS_PER_PANEL:.2f}"
                )
                issued = segments[0][1] - timedelta(minutes=30)
                self.add(
                    "mes.material_issue",
                    issue_id=self.number("issue", "{}", start=70001),
                    wo_no=wo_no,
                    item_code=item,
                    lot_no=f"{item.replace('-', '')}-{issued:%m%d}-{rng.randint(1, 9):02d}",
                    qty=amount,
                    unit="KG" if kind == "BLK" else "EA",
                    issued_at=issued,
                )
        wo["defect_qty"] = str(defect_total)
        if status == "완료":
            for seq, _, kind, *_ in ROUTING[product[:3]]:
                self.add(
                    "mes.work_order_operation",
                    wo_no=wo_no,
                    op_seq=seq,
                    equipment_code=FIXED_EQUIPMENT.get(kind, press),
                    status="완료",
                    remaining_qty=0,
                )
            self.history_wos.append(wo)
        return wo

    def _results(self, wo_no, seq, equipment, qty, segments) -> tuple[int, int]:
        """교대마다 실적 한 행. 양품은 실제 작업 시간에 비례해 나눈다."""
        rng = self.rng
        total = sum(seg[3] for seg in segments)
        good_total = ng_total = 0
        for i, (shift, s, e, worked) in enumerate(segments):
            good = (
                qty - good_total
                if i == len(segments) - 1
                else round(qty * worked / total / 10) * 10
            )
            ng = round(good * rng.uniform(0.002, 0.012))
            self.add(
                "mes.production_result",
                result_id=self.number("result", "{}", start=200001),
                wo_no=wo_no,
                op_seq=seq,
                equipment_code=equipment,
                worker_id=self.worker(LINE_OF[equipment], shift)["id"],
                shift=shift,
                started_at=s,
                ended_at=e,
                good_qty=good,
                ng_qty=ng,
            )
            good_total += good
            ng_total += ng
        return good_total, ng_total

    def _ship(self, so_no, product, qty, end, wo) -> None:
        rng = self.rng
        parts = (
            [qty] if qty <= 3000 or rng.random() < 0.5 else [qty // 2, qty - qty // 2]
        )
        day = end.date() + timedelta(days=1)
        for part in parts:
            lot = self.number("lot", f"LT{day:%y%m%d}-{{}}")
            self.add(
                "erp.shipment",
                ship_no=self.number("ship", f"DL{day:%y%m}-{{:04d}}"),
                so_no=so_no,
                item_code=product,
                ship_date=day,
                qty=part,
                carrier=rng.choice(["대한통운", "한진택배"]),
                lot_no=lot,
            )
            self.shipments_for_inspection.append((lot, wo, end))
            day += timedelta(days=1)

    # ---------- 시나리오 4 ----------
    def _book_ops(self, product, press, plan) -> list[tuple]:
        """시나리오용: [(공정, 수량, 가장 이른 시작)]을 시간표에 넣는다."""
        ops = []
        for seq, qty, earliest in plan:
            kind = next(op[2] for op in ROUTING[product[:3]] if op[0] == seq)
            equipment = FIXED_EQUIPMENT.get(kind, press)
            if self.history_free.get(equipment, earliest) > earliest:
                raise ValueError(f"시나리오 일정이 이력과 겹칩니다: {equipment}")
            segments = self.cal.book(
                equipment, earliest, self.op_minutes(product, seq, qty)
            )
            ops.append((seq, equipment, qty, segments))
        return ops

    def _scenario(self) -> None:
        """PDF 예시와 같은 상황을 우리 값으로: P103 고장 → 작업지시 A 중단, B는 P101 진행, C는 대기라 납기 위험.

        실적은 교대가 끝날 때 기록한다. 기준 시각(10/30 14:00)에는 주간 실적이 아직 없다.
        """
        prices = {i[0]: i[5] for i in ITEMS}
        self.history_free = dict(self.cal.free)
        self.add(
            "erp.sales_order",
            so_no=SO_MAIN,
            customer_code="CU0001",
            so_date=date(2026, 10, 20),
            due_date=date(2026, 11, 4),
            status="생산중",
            total_amount=f"{6000 * prices['BRK-210L'] + 5000 * prices['BRK-210R']:.2f}",
            sales_rep="영업팀",
        )
        self.add(
            "erp.sales_order_line",
            so_no=SO_MAIN,
            line_no=1,
            item_code="BRK-210L",
            qty=6000,
            unit_price="4300.00",
            shipped_qty=0,
        )
        self.add(
            "erp.sales_order_line",
            so_no=SO_MAIN,
            line_no=2,
            item_code="BRK-210R",
            qty=5000,
            unit_price="4300.00",
            shipped_qty=0,
        )
        self.add(
            "erp.sales_order",
            so_no=SO_RISK,
            customer_code="CU0002",
            so_date=date(2026, 10, 18),
            due_date=date(2026, 10, 31),
            status="접수",
            total_amount=f"{3000 * prices['BRK-210L']:.2f}",
            sales_rep="영업팀",
        )
        self.add(
            "erp.sales_order_line",
            so_no=SO_RISK,
            line_no=1,
            item_code="BRK-210L",
            qty=3000,
            unit_price="4300.00",
            shipped_qty=0,
        )
        d28, d29 = datetime(2026, 10, 28, 8), datetime(2026, 10, 29, 8)
        # A: 블랭킹 5,000개는 10/28에 끝냄. P103은 10/29 주간 성형 1,800개, 야간 트리밍 1,800개.
        # 이어서 두 번째 성형 묶음을 찍던 중 10/30 03:10 고장 (끝나지 않은 묶음은 실적이 없다)
        a_ops = self._book_ops(
            "BRK-210R",
            "P103",
            [(10, 5000, d28), (20, 1800, d29), (30, 1800, d29 + timedelta(hours=12))],
        )
        # D: 2라인 휠하우스 패널. 트리밍까지 끝내고 지금 W201에서 볼트 용접 중(실적 기록 전)
        d_ops = self._book_ops(
            "PNL-410",
            "P201",
            [
                (10, 4000, d28),
                (20, 4000, d29),
                (30, 4000, d29),
            ],
        )
        # B: 블랭킹 6,000개, P101 성형·트리밍 3,600개까지 (10/30 주간 실적은 아직 기록 전)
        b_ops = self._book_ops(
            "BRK-210L",
            "P101",
            [(10, 6000, d29), (20, 3600, d29 + timedelta(hours=1)), (30, 3600, d29)],
        )
        if a_ops[-1][3][-1][2] > BREAKDOWN_AT or b_ops[-1][3][-1][2] > datetime(
            2026, 10, 30, 5
        ):
            raise ValueError("시나리오 실적이 고장·기준 시각 뒤에 끝납니다")
        b = self._work_order(
            SO_MAIN,
            "BRK-210L",
            6000,
            "P101",
            b_ops,
            status="진행",
            wo_no=WO_B,
            planned=(date(2026, 10, 29), date(2026, 10, 31)),
        )
        a = self._work_order(
            SO_MAIN,
            "BRK-210R",
            5000,
            "P103",
            a_ops,
            status="진행",
            wo_no=WO_A,
            planned=(date(2026, 10, 28), date(2026, 10, 31)),
        )
        c = self.add(
            "mes.work_order",
            wo_no=WO_C,
            product_code="BRK-210L",
            die_drawing_code="DWG-BRK-210L",
            status="대기",
            priority="보통",
            line="1라인",
            planned_qty=3000,
            produced_qty=0,
            defect_qty=0,
            planned_start=date(2026, 11, 2),
            planned_end=date(2026, 11, 3),
            actual_start="",
            actual_end="",
            so_ref=compact(SO_RISK),
        )
        # 2라인은 평소처럼 휠하우스 패널을 만든다: D는 용접 중, E는 대기
        line2_orders = []
        for so_date, due, qty in [
            (date(2026, 10, 23), date(2026, 11, 5), 4000),
            (date(2026, 10, 27), date(2026, 11, 10), 3000),
        ]:
            so_no = self.number("so", f"OD-{so_date:%y%m}-{{:03d}}")
            self.add(
                "erp.sales_order",
                so_no=so_no,
                customer_code="CU0003",
                so_date=so_date,
                due_date=due,
                status="생산중" if qty == 4000 else "접수",
                total_amount=f"{qty * prices['PNL-410']:.2f}",
                sales_rep="영업팀",
            )
            self.add(
                "erp.sales_order_line",
                so_no=so_no,
                line_no=1,
                item_code="PNL-410",
                qty=qty,
                unit_price=f"{prices['PNL-410']:.2f}",
                shipped_qty=0,
            )
            line2_orders.append(so_no)
        d = self._work_order(
            line2_orders[0],
            "PNL-410",
            4000,
            "P201",
            d_ops,
            status="진행",
            planned=(date(2026, 10, 28), date(2026, 10, 31)),
        )
        e = self.add(
            "mes.work_order",
            wo_no=self.number("wo", "JB2610-{:04d}", start=101),
            product_code="PNL-410",
            die_drawing_code="DWG-PNL-410",
            status="대기",
            priority="보통",
            line="2라인",
            planned_qty=3000,
            produced_qty=0,
            defect_qty=0,
            planned_start=date(2026, 11, 2),
            planned_end=date(2026, 11, 3),
            actual_start="",
            actual_end="",
            so_ref=compact(line2_orders[1]),
        )
        for wo, press, states in [
            (b, "P101", ["완료", "진행", "진행", "대기"]),
            (a, "P103", ["완료", "중단", "중단", "대기"]),
            (c, "P101", ["대기"] * 4),
            (d, "P201", ["완료", "완료", "완료", "진행", "대기"]),
            (e, "P201", ["대기"] * 5),
        ]:
            for (seq, _, kind, *_), state in zip(
                ROUTING[wo["product_code"][:3]], states
            ):
                finished = sum(
                    int(r["good_qty"])
                    for r in self.rows["mes.production_result"]
                    if r["wo_no"] == wo["wo_no"] and r["op_seq"] == str(seq)
                )
                self.add(
                    "mes.work_order_operation",
                    wo_no=wo["wo_no"],
                    op_seq=seq,
                    equipment_code=FIXED_EQUIPMENT.get(kind, press),
                    status=state,
                    remaining_qty=0
                    if state == "완료"
                    else int(wo["planned_qty"]) - finished,
                )
        self.add(
            "mes.downtime",
            downtime_id=DT_BREAKDOWN,
            equipment_code="P103",
            wo_no=WO_A,
            reason_code="BRK",
            reason="슬라이드 유압 누유",
            started_at=BREAKDOWN_AT,
            ended_at="",
            minutes="",
        )
        self.add(
            "mes.maintenance_order",
            mo_no=MO_BREAKDOWN,
            target_type="설비",
            target_code="P103",
            mo_type="고장",
            status="진행",
            planned_date=date(2026, 10, 30),
            completed_at="",
            technician="보전팀",
            parts_cost="1650000.00",
            note="슬라이드 유압 실린더 씰 교체",
        )
        self.scenario = {"A": a, "B": b, "C": c, "D": d, "E": e}
        for case_id, text, rows in [
            (
                "D1",
                "작업지시 수주 참조(so_ref) — ERP 수주 번호와 표기가 다름 (2610021 ↔ OD-2610-021)",
                [WO_A, WO_B, WO_C],
            ),
            ("D2", f"공정별 배정: {WO_A}의 성형·트리밍 = P103", [WO_A]),
            (
                "D3",
                "P103 고장(비가동 진행 중)·정비 진행, 진동이 10/28부터 상한 초과",
                [DT_BREAKDOWN, MO_BREAKDOWN],
            ),
            (
                "D4",
                "공정 최소 톤수: 브래킷 성형 400톤 → P201(300톤) 불가",
                ["BRK-210R"],
            ),
            (
                "D5",
                f"{SO_RISK} ↔ {WO_C}: 작업지시가 대기(11/2 시작)라 납기 10/31 위험",
                [SO_RISK, WO_C],
            ),
        ]:
            self.plant(case_id, text, "시나리오 4", rows)

    # ---------- 비가동·정비·센서 ----------
    def _plan_downtimes(self) -> None:
        """비가동을 먼저 정해 설비 시간표에서 비워 둔다. 행은 이력을 만든 뒤 _record_downtimes가 쓴다."""
        rng = self.rng
        reasons = {
            "BRK": ["유압 압력 저하", "클러치 소음", "피더 센서 오작동", "가이드 마모"],
            "DIE": ["금형 교체"],
            "MAT": ["코일 대기", "블랭크 대기"],
            "PM": ["월간 예방 정비", "윤활 계통 점검"],
            "QA": ["치수 불량 확인"],
        }
        busy = defaultdict(list)
        self.downtime_events = []
        for reason, count in [
            ("BRK", 6),
            ("DIE", 10),
            ("MAT", 6),
            ("PM", 6),
            ("QA", 3),
        ]:
            for _ in range(count):
                for _attempt in range(50):
                    code = rng.choice(
                        ["B101", "P101", "P103", "P201"]
                        + (["I101"] if reason == "QA" else [])
                    )
                    day = HISTORY_START + timedelta(
                        days=rng.randint(0, (HISTORY_END - HISTORY_START).days)
                    )
                    start = datetime.combine(day, datetime.min.time()) + timedelta(
                        hours=rng.choice([0, 6, 12]),
                        minutes=rng.choice(range(0, 60, 10)),
                    )
                    minutes = {
                        "BRK": rng.randrange(60, 481, 30),
                        "DIE": rng.randint(30, 60),
                        "MAT": rng.randint(15, 90),
                        "PM": rng.randrange(120, 361, 30),
                        "QA": rng.randint(20, 60),
                    }[reason]
                    end = start + timedelta(minutes=minutes)
                    working_day = day.weekday() < 5
                    if working_day and not any(
                        s < end and start < e for s, e in busy[code]
                    ):
                        break
                busy[code].append((start, end))
                self.cal.blocked[code].append((start, end))
                self.downtime_events.append(
                    (start, end, code, reason, rng.choice(reasons[reason]), minutes)
                )

    def _record_downtimes(self) -> None:
        rng = self.rng
        fixes = {
            "유압 압력 저하": "유압 펌프 필터 교체",
            "클러치 소음": "클러치 라이닝 교체",
            "피더 센서 오작동": "피더 센서 교체",
            "가이드 마모": "가이드 라이너 교체",
            "월간 예방 정비": "월간 PM, 윤활유 교환",
            "윤활 계통 점검": "윤활 배관 점검·보충",
        }
        spans = defaultdict(list)
        for r in self.rows["mes.production_result"]:
            spans[r["equipment_code"]].append(
                (r["started_at"], r["ended_at"], r["wo_no"])
            )
        for start, end, code, reason, text, minutes in sorted(self.downtime_events):
            at = fmt(start)
            running = next(
                (wo for s, e, wo in spans[code] if s <= at <= e),
                "",
            )
            self.add(
                "mes.downtime",
                downtime_id=self.number("dt", "DT-{:04d}", start=401),
                equipment_code=code,
                wo_no="" if reason == "PM" else running,
                reason_code=reason,
                reason=text,
                started_at=start,
                ended_at=end,
                minutes=minutes,
            )
            if reason in ("BRK", "PM"):
                self.add(
                    "mes.maintenance_order",
                    mo_no=self.number("mo", "RP-26-{:03d}"),
                    target_type="설비",
                    target_code=code,
                    mo_type="고장" if reason == "BRK" else "예방",
                    status="완료",
                    planned_date=start.date(),
                    completed_at=end,
                    technician="보전팀",
                    parts_cost=f"{(rng.randrange(150000, 1200001, 10000) if reason == 'BRK' else rng.choice([0, 80000])):.2f}",
                    note=fixes[text],
                )
        # 금형 정비 담당은 팀 대신 보전팀 직원 이름으로 적혀 있다(부서 표와 안 이어지는 값)
        technician = next(
            e["emp_name"]
            for e in self.rows["erp.employee"]
            if e["emp_no"] == schema.TECHNICIAN_EMP_NO
        )
        for die, day in [
            ("MD-BRK210L-01", date(2026, 9, 3)),
            ("MD-RNF320L-01", date(2026, 9, 24)),
        ]:
            self.add(
                "mes.maintenance_order",
                mo_no=self.number("mo", "RP-26-{:03d}"),
                target_type="금형",
                target_code=die,
                mo_type="예방",
                status="완료",
                planned_date=day,
                completed_at=datetime.combine(day, datetime.min.time())
                + timedelta(hours=15),
                technician=technician,
                parts_cost="0.00",
                note="펀치 연마",
            )
        self.rows["mes.downtime"].sort(key=lambda r: r["started_at"])
        self.rows["mes.maintenance_order"].sort(key=lambda r: r["mo_no"])

    def _sensors(self) -> None:
        """매일 09시 진동·유압 오일 온도. 고장 전날·당일은 진동이 오른다.

        P103 진동은 10/28부터 상한 4.5를 넘는다(정비 담당 질문 'P103 진동이 언제부터 상한을 넘었어?').
        """
        rng = self.extra_rng
        fixed = {
            "P103": [2.2, 2.4, 2.1, 2.3, 2.6, 3.1, 3.8, 4.9, 5.7],
            "P101": [2.1, 2.2, 2.0, 2.3, 2.2, 2.4, 2.3, 2.2, 2.4],
        }
        base = {"B101": 1.8, "P101": 2.2, "P103": 2.2, "P201": 2.6}
        warn = defaultdict(set)
        for start, _, code, reason, *_ in self.downtime_events:
            if reason == "BRK":
                warn[code] |= {start.date(), start.date() - timedelta(days=1)}
        day = HISTORY_START
        while day <= NOW.date():
            for code, normal in base.items():
                if day.weekday() == 6 or (code == "P103" and day == NOW.date()):
                    continue
                offset = (day - date(2026, 10, 21)).days
                if code in fixed and 0 <= offset < len(fixed[code]):
                    vibration = fixed[code][offset]
                else:
                    vibration = normal + rng.uniform(-0.3, 0.3)
                    if day in warn[code]:
                        vibration += rng.uniform(1.0, 1.6)
                at = datetime.combine(day, datetime.min.time()) + timedelta(hours=9)
                temp = 41 + rng.uniform(-3, 4) + (vibration - normal) * 4
                for metric, value, limit in [
                    ("vibration_mm_s", vibration, "4.50"),
                    ("temp_c", temp, "60.00"),
                ]:
                    self.add(
                        "mes.sensor_reading",
                        equipment_code=code,
                        measured_at=at,
                        metric=metric,
                        value=f"{value:.2f}",
                        threshold_high=limit,
                    )
            day += timedelta(days=1)
        self.add(
            "mes.sensor_reading",
            equipment_code="P103",
            measured_at=datetime(2026, 10, 30, 2, 50),
            metric="vibration_mm_s",
            value="6.40",
            threshold_high="4.50",
        )
        self.rows["mes.sensor_reading"].sort(
            key=lambda r: (r["measured_at"], r["equipment_code"], r["metric"])
        )

    # ---------- 구매·재고 ----------
    def _purchasing(self) -> None:
        """주마다 다음 주 투입량만큼 발주한다. 입고는 발주 5~7일 뒤, 10/23 이후 발주는 아직 입고 전."""
        rng = self.rng
        prices = {i[0]: i[5] for i in ITEMS}
        vendor_of = {"CL-440": "SP0001", "CL-590": "SP0003", "BT-M8": "SP0002"}
        use = defaultdict(float)  # (품목, 주 시작일) → 투입량
        for r in self.rows["mes.material_issue"]:
            day = date.fromisoformat(r["issued_at"][:10])
            use[(r["item_code"], day - timedelta(days=day.weekday()))] += float(
                r["qty"]
            )
        week = HISTORY_START - timedelta(days=HISTORY_START.weekday() + 7)
        orders = []
        while week <= NOW.date():
            for item in vendor_of:
                if item == "BT-M8" and (week.day > 7 or week.month == 7):
                    continue  # 볼트는 달마다 한 번
                horizon = 4 if item == "BT-M8" else 1
                need = sum(
                    use[(item, week + timedelta(days=7 * (k + 1)))]
                    for k in range(horizon)
                )
                if need <= 0:
                    continue
                step = 1000 if item != "BT-M8" else 2000
                qty = math.ceil(need * rng.uniform(1.0, 1.15) / step) * step
                orders.append((week + timedelta(days=rng.randint(0, 2)), item, qty))
            week += timedelta(days=7)
        po_lines = []
        for day, item, qty in sorted(orders):
            po_no = self.number("po", f"PO{day:%y%m}-{{:03d}}")
            arrive = day > date(2026, 10, 22)
            split = not arrive and rng.random() < 0.2
            received = 0 if arrive else qty
            status = "발주" if arrive else "입고완료"
            self.add(
                "erp.purchase_order",
                po_no=po_no,
                vendor_code=vendor_of[item],
                po_date=day,
                due_date=day + timedelta(days=7),
                status=status,
                total_amount=f"{qty * prices[item]:.2f}",
                buyer="구매팀",
                remark="" if rng.random() < 0.85 else "납기 조정",
            )
            self.add(
                "erp.purchase_order_line",
                po_no=po_no,
                line_no=1,
                item_code=item,
                qty=qty,
                unit_price=f"{prices[item]:.2f}",
                received_qty=received,
                due_date=day + timedelta(days=7),
            )
            parts = [received // 2, received - received // 2] if split else [received]
            for i, part in enumerate(p for p in parts if p):
                rejected = rng.choice([0, 0, 0, 20, 50]) if item != "BT-M8" else 0
                gr_day = day + timedelta(days=5 + i * 2)
                self.add(
                    "erp.goods_receipt",
                    gr_no=self.number("gr", f"RC{gr_day:%y%m}-{{:04d}}"),
                    po_no=po_no,
                    item_code=item,
                    gr_date=gr_day,
                    qty=part,
                    accepted_qty=part - rejected,
                    rejected_qty=rejected,
                    warehouse="원자재창고" if item != "BT-M8" else "부자재창고",
                    inspector="품질팀",
                )
            if status == "입고완료":
                po_lines.append(po_no)
        self.cases["A2_po"] = po_lines[len(po_lines) // 2]

    def _stock(self) -> None:
        """현재 재고 = 기초 재고 + 들어온 양 - 나간 양. 예약은 대기 작업지시·미출하 수주 몫."""
        rng = self.rng
        moves = defaultdict(list)  # 품목 → [(날짜, 수량)]
        for g in self.rows["erp.goods_receipt"]:
            moves[g["item_code"]].append((g["gr_date"], float(g["accepted_qty"])))
        for m in self.rows["mes.material_issue"]:
            moves[m["item_code"]].append((m["issued_at"][:10], -float(m["qty"])))
        wo_product = {
            w["wo_no"]: w["product_code"] for w in self.rows["mes.work_order"]
        }
        for r in self.rows["mes.production_result"]:
            product = wo_product[r["wo_no"]]
            ops = ROUTING[product[:3]]
            last_seq = max(op[0] for op in ops if op[2] != "INS")
            day, good = r["ended_at"][:10], int(r["good_qty"])
            sf = SF_OF[product[:3]]
            if r["op_seq"] == str(last_seq):
                moves[product].append((day, good))
            if sf and r["op_seq"] == "10":
                moves[sf].append((day, good))
            if sf and r["op_seq"] == "20":
                moves[sf].append((day, -good))
        for s in self.rows["erp.shipment"]:
            moves[s["item_code"]].append((s["ship_date"], -int(s["qty"])))
        reserved = defaultdict(float)
        for w in self.rows["mes.work_order"]:
            if w["status"] == "대기":
                product = w["product_code"]
                qty = int(w["planned_qty"])
                reserved[COIL[product]] += qty * KG_PER_PIECE[product]
                if product == "PNL-410":
                    reserved["BT-M8"] += qty * BOLTS_PER_PANEL
        for line in self.rows["erp.sales_order_line"]:
            order = next(
                o for o in self.rows["erp.sales_order"] if o["so_no"] == line["so_no"]
            )
            if order["status"] in ("접수", "생산중"):
                reserved[line["item_code"]] += int(line["qty"]) - int(
                    line["shipped_qty"]
                )
        places = [
            ("원자재창고", "CL-440"),
            ("원자재창고", "CL-590"),
            ("부자재창고", "BT-M8"),
        ]
        places += [("재공품창고", i[0]) for i in ITEMS if i[4] == "SF"]
        places += [("완제품창고", i[0]) for i in ITEMS if i[4] == "FG"]
        safety = {i[0]: i[6] for i in ITEMS}
        for warehouse, item in places:
            flow = sorted(moves[item])
            running = low = 0.0
            for _, q in flow:
                running += q
                low = min(low, running)
            opening = (
                0
                if warehouse == "재공품창고"
                else -low + safety[item] + rng.randrange(0, 2001, 100)
            )
            qty = round(opening + running)
            held = min(qty, round(reserved[item]))
            ins = [d for d, q in flow if q > 0]
            outs = [d for d, q in flow if q < 0]
            self.add(
                "erp.stock",
                warehouse=warehouse,
                item_code=item,
                qty=qty,
                reserved_qty=held,
                available_qty=qty - held,
                last_in_date=max(ins) if ins else "",
                last_out_date=max(outs) if outs else "",
            )

    # ---------- 회계 ----------
    def _accounting(self) -> None:
        for code, name, kind in [
            ("1080", "외상매출금", "자산"),
            ("1350", "부가세대급금", "자산"),
            ("1460", "원재료", "자산"),
            ("2510", "외상매입금", "부채"),
            ("2550", "부가세예수금", "부채"),
            ("4010", "제품매출", "수익"),
            ("8110", "복리후생비", "비용"),
        ]:
            self.add(
                "erp.gl_account",
                account_code=code,
                account_name=name,
                account_type=kind,
                parent_code="",
                use_yn="Y",
            )
        prices = {i[0]: i[5] for i in ITEMS}
        monthly = defaultdict(int)
        for s in self.rows["erp.shipment"]:
            monthly[
                (
                    s["so_no"]
                    and next(
                        o["customer_code"]
                        for o in self.rows["erp.sales_order"]
                        if o["so_no"] == s["so_no"]
                    ),
                    s["ship_date"][:7],
                )
            ] += int(s["qty"]) * prices[s["item_code"]]
        for (customer, month), supply in sorted(monthly.items()):
            day = date.fromisoformat(month + "-28")
            vat = supply // 10
            invoice = self.number("ar", f"AR-{day:%y%m}-{{:03d}}")
            collected = day < date(2026, 10, 1)
            self.add(
                "erp.sales_invoice",
                invoice_no=invoice,
                customer_code=customer,
                invoice_date=day,
                supply_amount=f"{supply:.2f}",
                vat=f"{vat:.2f}",
                total_amount=f"{supply + vat:.2f}",
                collected_yn="Y" if collected else "N",
                collected_date=day + timedelta(days=20) if collected else "",
            )
            self._entry(
                day,
                customer,
                [("1080", supply + vat, 0), ("4010", 0, supply), ("2550", 0, vat)],
                f"{month} 제품 매출",
            )
        purchases = defaultdict(int)
        po_vendor = {
            p["po_no"]: p["vendor_code"] for p in self.rows["erp.purchase_order"]
        }
        for g in self.rows["erp.goods_receipt"]:
            purchases[(po_vendor[g["po_no"]], g["gr_date"][:7])] += (
                int(g["accepted_qty"]) * prices[g["item_code"]]
            )
        entries = []
        for (vendor, month), supply in sorted(purchases.items()):
            day = date.fromisoformat(month + "-27")
            vat = supply // 10
            paid = day < date(2026, 10, 1)
            self.add(
                "erp.purchase_invoice",
                invoice_no=self.number("ap", f"AP-{day:%y%m}-{{:03d}}"),
                vendor_code=vendor,
                invoice_date=day,
                supply_amount=f"{supply:.2f}",
                vat=f"{vat:.2f}",
                total_amount=f"{supply + vat:.2f}",
                paid_yn="Y" if paid else "N",
                paid_date=day + timedelta(days=25) if paid else "",
            )
            entries.append(
                self._entry(
                    day,
                    vendor,
                    [("1460", supply, 0), ("1350", vat, 0), ("2510", 0, supply + vat)],
                    f"{month} 원재료 매입",
                )
            )
        # 검사 A4: 매입 분개 하나에서 부가세 줄이 빠져 차변 < 대변
        broken = entries[len(entries) // 2]
        self.rows["erp.journal_entry"] = [
            r
            for r in self.rows["erp.journal_entry"]
            if not (r["entry_no"] == broken and r["account_code"] == "1350")
        ]
        self.cases["A4_entry"] = broken

    def _entry(self, day, vendor, lines, text) -> str:
        entry_no = self.number("je", f"JV-{day:%y%m}-{{:04d}}")
        for i, (account, debit, credit) in enumerate(lines, 1):
            self.add(
                "erp.journal_entry",
                entry_no=entry_no,
                line_no=i,
                entry_date=day,
                account_code=account,
                vendor_code=vendor,
                debit=f"{debit:.2f}",
                credit=f"{credit:.2f}",
                description=text,
            )
        return entry_no

    def _attendance(self) -> None:
        """8/3부터 기준 시각까지. 평일은 모두 출근(가끔 연차), 토요일은 실적이 있는 작업자만 특근.

        그날 실적이 있는 작업자는 연차를 쓰지 않는다. 야간조의 근무일은 교대가 시작한 날이다.
        """
        rng = self.extra_rng
        workers = {f"GP{int(w['id']):04d}": w for w in self.workers}
        worked = set()
        for r in self.rows["mes.production_result"]:
            _, day = shift_of(datetime.fromisoformat(r["started_at"]))
            worked.add((f"GP{int(r['worker_id']):04d}", day))
        day = HISTORY_START
        while day <= NOW.date():
            for emp in (e["emp_no"] for e in self.rows["erp.employee"]):
                night = emp in workers and workers[emp]["shift"] == "야간"
                busy = (emp, day) in worked
                if day.weekday() == 6 or (day.weekday() == 5 and not busy):
                    continue
                if day == NOW.date() and night:
                    continue  # 오늘 야간은 아직 시작 전
                leave = not busy and rng.random() < 0.04
                late = not leave and rng.random() < 0.05
                overtime = 0 if leave else rng.choice([0, 0, 30, 60, 90])
                start = datetime.combine(day, datetime.min.time()) + timedelta(
                    hours=20 if night else 8
                )
                clock_in = start + (
                    timedelta(minutes=rng.randint(12, 25))
                    if late
                    else -timedelta(minutes=rng.randint(1, 15))
                )
                clock_out = start + timedelta(
                    hours=9, minutes=overtime + rng.randint(5, 25)
                )
                still_working = clock_out > NOW
                self.add(
                    "erp.attendance",
                    emp_no=emp,
                    work_date=day,
                    clock_in="" if leave else f"{clock_in:%H:%M}",
                    clock_out="" if leave or still_working else f"{clock_out:%H:%M}",
                    work_type="연차" if leave else "근무",
                    overtime_min=0 if leave or still_working else overtime,
                    late_yn="Y" if late else "N",
                )
            day += timedelta(days=1)

    # ---------- 정합성 검사 사례 (기업 정합성 예시 9건과 같은 종류, 우리 값) ----------
    def _plant_integrity_cases(self) -> None:
        rng = self.rng
        # 검사 A1: 출하 완료 수주 하나 (수주 상세 출하 수량 = 출하 합)
        shipped = [
            o["so_no"]
            for o in self.rows["erp.sales_order"]
            if o["status"] == "출하완료" and o["so_no"].startswith("OD-2609")
        ]
        self.cases["A1_so"] = shipped[len(shipped) // 2]
        # 검사 A3: 완료 작업지시 하나의 불량 수량이 실적 ng 합·불량 기록 합과 모두 다름
        candidates = [
            w for w in self.history_wos if int(w["defect_qty"]) > 10 and w["so_ref"]
        ]
        wo = candidates[len(candidates) // 3]
        records = [d for d in self.rows["mes.defect"] if d["wo_no"] == wo["wo_no"]]
        records[0]["qty"] = str(max(1, int(records[0]["qty"]) - 9))
        wo["defect_qty"] = str(int(wo["defect_qty"]) + 10)
        self.cases["A3_wo"] = wo["wo_no"]
        # 검사 K5: 자재 투입 로트 하나 (입고 표에 로트 컬럼이 없어 대조 불가)
        self.cases["K5_lot"] = self.rows["mes.material_issue"][
            len(self.rows["mes.material_issue"]) // 2
        ]["lot_no"]
        # 수주 참조 표기 차이·결측·없는 번호
        picks = rng.sample(
            [w for w in self.history_wos if w["wo_no"] != wo["wo_no"]], 17
        )
        full, blank, missing = picks[:12], picks[12:15], picks[15:]
        for w in full:
            w["so_ref"] = f"OD-{w['so_ref'][:4]}-{w['so_ref'][4:]}"
        for w in blank:
            w["so_ref"] = ""
        for i, w in enumerate(missing):
            w["so_ref"] = f"{w['so_ref'][:4]}{970 + i}"
        self.plant(
            "S-FULL",
            "수주 참조가 이미 ERP 표기(OD-YYMM-NNN)로 입력됨",
            "mes.work_order.so_ref",
            [w["wo_no"] for w in full],
        )
        self.plant(
            "S-BLANK",
            "수주 참조가 빈 작업지시 (오류 결측)",
            "mes.work_order.so_ref",
            [w["wo_no"] for w in blank],
        )
        self.plant(
            "S-MISSING",
            "ERP에 없는 수주 번호를 참조",
            "mes.work_order.so_ref",
            [w["wo_no"] for w in missing],
            {w["wo_no"]: w["so_ref"] for w in missing},
        )
        # 품질 검사: 출하 로트마다 최종 검사. 가장 최근 출하 로트 하나는 검사 기록이 없다(페르소나 질문 '로트 검사 결과')
        for lot, wo_row, finished in self.shipments_for_inspection[:-1]:
            ok = rng.random() > 0.05
            measured = round(
                rng.uniform(8.96, 9.04) if ok else rng.choice([8.91, 9.08]), 3
            )
            self.add(
                "mes.inspection",
                inspection_id=self.number("insp", "{}", start=90001),
                wo_no=wo_row["wo_no"],
                lot_no=lot,
                inspection_type="최종",
                item="홀 지름",
                spec_low="8.950",
                spec_high="9.050",
                measured=f"{measured:.3f}",
                result="OK" if 8.95 <= measured <= 9.05 else "NG",
                inspected_at=finished + timedelta(hours=1),
            )
        self.cases["P3_lot"] = self.shipments_for_inspection[-1][0]
        for key, value in self.cases.items():
            self.plant(f"CASE-{key}", "정합성 검사 대상", key, [value])

    # ---------- 인수인계 메모 표 ----------
    def handover_notes(self, notes) -> None:
        """메모 원문을 그대로 MES 표에 넣는다. 정답의 원문 위치(start·end)가 content 기준으로 그대로 맞는다."""
        for doc in sorted(notes, key=lambda d: d.meta["period"]["start"]):
            start = datetime.fromisoformat(doc.meta["period"]["start"])
            self.add(
                "mes.handover_note",
                note_id=doc.doc_id,
                shift_date=start.date(),
                shift=doc.meta["shift"],
                author_id=doc.meta["author"].split(":", 1)[1],
                written_at=datetime.fromisoformat(doc.meta["period"]["end"]),
                content=doc.text,
            )

    # ---------- 변경 이력 (최근 3일) ----------
    def change_logs(self) -> None:
        since = datetime(2026, 10, 28)
        events = {"erp": [], "mes": []}
        for key, column, op in [
            ("erp.shipment", "ship_date", "INSERT"),
            ("erp.purchase_order", "po_date", "INSERT"),
            ("erp.goods_receipt", "gr_date", "INSERT"),
            ("erp.attendance", "work_date", "INSERT"),
            ("mes.production_result", "ended_at", "INSERT"),
            ("mes.downtime", "started_at", "INSERT"),
            ("mes.maintenance_order", "planned_date", "INSERT"),
            ("mes.handover_note", "written_at", "INSERT"),
        ]:
            table = schema.TABLE_BY_KEY[key]
            for row in self.rows[key]:
                if row[column] and row[column] >= f"{since:%Y-%m-%d}":
                    at = (
                        row[column] if " " in row[column] else row[column] + " 09:00:00"
                    )
                    events[table.source].append(
                        (
                            at,
                            table.name,
                            "|".join(row[k] for k in table.primary_key),
                            op,
                        )
                    )
        events["mes"].append(
            (f"{BREAKDOWN_AT:%Y-%m-%d %H:%M:%S}", "equipment", "P103", "UPDATE")
        )
        events["mes"].append(
            (
                f"{BREAKDOWN_AT:%Y-%m-%d %H:%M:%S}",
                "work_order_operation",
                f"{WO_A}|20",
                "UPDATE",
            )
        )
        for source, items in events.items():
            for i, (at, table, key, op) in enumerate(sorted(items), 1):
                self.add(
                    f"{source}.change_log",
                    change_id=i,
                    table_name=table,
                    row_key=key,
                    operation=op,
                    changed_at=at,
                )


def _db_value(sql_type: str, value: str) -> str:
    if sql_type.startswith("numeric("):
        scale = int(sql_type.rstrip(")").split(",")[1])
        return str(Decimal(value).quantize(Decimal(1).scaleb(-scale)))
    if sql_type == "time" and len(value) == 5:
        return f"{value}:00"
    return value


def db_text(table: schema.Table, rows: list[dict]) -> None:
    """DB에서 내보낸 모양으로 맞춘다: numeric(p,s)는 소수 s자리, time은 HH:MM:SS."""
    for name, sql_type, _ in table.columns:
        for r in rows:
            if r[name] not in ("", None):
                r[name] = _db_value(sql_type, str(r[name]))


def write_csv(path: Path, header, rows) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=header, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        f.write(text)


def write_checksums(folder: Path) -> None:
    """폴더 안 파일의 SHA-256 목록. 배포 뒤 파일이 바뀌었는지 `sha256sum -c CHECKSUMS.sha256`로 확인한다."""
    lines = []
    for path in sorted(p for p in folder.rglob("*") if p.is_file()):
        if path.name == "CHECKSUMS.sha256":
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        lines.append(f"{digest}  {path.relative_to(folder).as_posix()}")
    write_text(folder / "CHECKSUMS.sha256", "\n".join(lines) + "\n")


def write_json(path: Path, content) -> None:
    write_text(path, json.dumps(content, ensure_ascii=False, indent=2) + "\n")


RELEASE = "v1.0"


def folders_for(root: Path, version: str) -> dict[str, Path]:
    """원천(data-), 정답(gold-), 시연용 원천 수정분(scenario-)을 다른 폴더에 둔다. 정답은 AI 입력 폴더 밖에 있다."""
    return {
        "sample": root / f"data-{version}",
        "gold": root / f"gold-{version}",
        "scenario": root / f"scenario-{version}",
    }


def generate(
    version: str = RELEASE,
    seed: int = 20261023,
    root: Path = DATA_ROOT,
    force: bool = False,
) -> dict:
    from app.dataset import docs
    from app.dataset import gold as gold_module
    from app.dataset.notes import build_notes, maintenance_docs

    folders = folders_for(root, version)
    existing = [str(f) for f in folders.values() if f.exists()]
    if existing and not force:
        raise FileExistsError(
            f"이미 있는 버전입니다. 다시 만들려면 --force: {existing}"
        )
    for folder in folders.values():
        if folder.exists():
            shutil.rmtree(folder)
    builder = Builder(seed)
    builder.build()
    notes = build_notes(
        builder.rows, seed, HISTORY_START, NOW.date() - timedelta(days=1)
    )
    builder.handover_notes(notes)
    builder.change_logs()
    checks = run_checks(builder.rows, builder.cases)
    verdicts = {c.check_id: c.verdict for c in checks}
    if verdicts != EXPECTED:
        raise ValueError(f"정합성 검사 9건이 기대와 다릅니다: {verdicts}")
    sample = folders["sample"]
    for source in ("erp", "mes"):
        for table in (t for t in schema.TABLES if t.source == source):
            db_text(table, builder.rows[table.key])
            write_csv(
                sample / source / f"{table.name}.csv",
                table.column_names,
                builder.rows[table.key],
            )
        write_json(sample / source / "_schema.json", schema.schema_json(source))
        write_text(sample / source / "schema.sql", schema.schema_sql(source))
    counts = {t.key: len(builder.rows[t.key]) for t in schema.TABLES}
    for name, text in docs.load_scripts(counts).items():
        write_text(sample / name, text)
    write_text(
        sample / "README.md",
        docs.data_readme(version, counts, f"{NOW:%Y-%m-%d %H:%M}"),
    )
    summary = gold_module.write_gold(
        builder,
        checks,
        folders["gold"],
        NOW,
        notes,
        maintenance_docs(builder.rows),
        version,
    )
    gold_module.write_s5_fix(builder, folders["scenario"])

    def read(name):
        return json.loads((folders["gold"] / name).read_text(encoding="utf-8"))

    write_text(
        folders["gold"] / "README.md",
        docs.gold_readme(
            f"gold-{version}",
            read("ontology.json"),
            read("mapping_rules.json"),
            read("scenario_answers.json"),
            read("planted_cases.json"),
        ),
    )
    for folder in folders.values():
        write_checksums(folder)
    return {
        "folders": {k: str(v) for k, v in folders.items()},
        "rows": sum(counts.values()),
        "gold": summary,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="00프레스(가상) 원천 데이터·정답셋 생성"
    )
    parser.add_argument("--version", default=RELEASE)
    parser.add_argument("--seed", type=int, default=20261023)
    parser.add_argument("--out", type=Path, default=DATA_ROOT)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    print(
        json.dumps(
            generate(args.version, args.seed, args.out, args.force),
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
