"""GMS로 보내기 전에 메모·자유 입력 글의 개인정보를 가린다.

가리는 것: 전화번호, 이메일, 주민등록번호, '성 + 직함' 형태의 사람 이름(예: 김 대리, 박민수 반장).
직함 없이 이름만 쓴 경우는 찾지 못한다. 거래처·금액 같은 기업 정보는 팀 전송 기준이 정해지면 추가한다.
"""

import re
from dataclasses import dataclass

MASK = "***"
SURNAMES = "김이박최정강조윤장임한오서신권황안송류홍전고문양손배백허유남심노하곽성차주우구민진나지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국어은편용"
TITLES = "대리|과장|차장|부장|주임|반장|조장|사원|팀장|기사|씨"
# 성씨 글자로 시작하지만 사람 이름이 아닌 현장 단어 (예: 정비 기사, 성형 반장)
NOT_NAMES = {
    "정비",
    "성형",
    "보전",
    "품질",
    "생산",
    "금형",
    "설비",
    "현장",
    "주간",
    "야간",
    "검사",
    "용접",
    "공정",
    "자재",
    "구매",
    "영업",
    "관리",
    "안전",
    "조립",
    "전기",
    "기계",
    "도장",
    "출하",
    "물류",
    "공무",
    "외주",
    "신입",
    "선임",
    "수석",
    "반장",
}

PATTERNS = [
    # \b는 한글도 글자로 봐서 '…5678로'처럼 조사가 붙으면 놓친다. 숫자·영문 기준으로 경계를 본다
    ("RRN", re.compile(r"(?<!\d)\d{6}-[1-4]\d{6}(?!\d)")),
    (
        "PHONE",
        re.compile(
            r"(?<!\d)(?:01[016-9]-?\d{3,4}-?\d{4}|0\d{1,2}-\d{3,4}-\d{4})(?!\d)"
        ),
    ),
    (
        "EMAIL",
        re.compile(
            r"(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+"
        ),
    ),
    (
        "NAME",
        re.compile(rf"(?<![가-힣])([{SURNAMES}][가-힣]{{0,2}})(\s?)(?=(?:{TITLES}))"),
    ),
]


@dataclass
class Masked:
    text: str
    kinds: list[str]  # 가린 종류 (로그용. 원래 값은 남기지 않는다)


def mask_text(text: str) -> Masked:
    kinds = []
    for kind, pattern in PATTERNS:

        def replace(match, kind=kind):
            kinds.append(kind)
            if kind == "NAME":
                if match.group(1) in NOT_NAMES:
                    return match.group(0)
                return MASK + match.group(2)
            return MASK

        text = pattern.sub(replace, text)
    return Masked(text, kinds)
