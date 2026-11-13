"""검토 화면용 한국어 제목·설명과 바뀐 점수."""

import pytest

from app.proposers import confidence
from app.proposers.readable import josa, readable


@pytest.mark.parametrize(
    "word, pair, expected",
    [
        ("작업지시", "이/가", "작업지시가"),
        ("직원", "이/가", "직원이"),
        ("수주", "을/를", "수주를"),
        ("제품별 공정", "을/를", "제품별 공정을"),
        ("사번", "으로/로", "사번으로"),
        ("수주 참조 번호", "으로/로", "수주 참조 번호로"),
        ("작업자 번호·라인", "으로/로", "작업자 번호·라인으로"),
        ("메일", "으로/로", "메일로"),  # ㄹ 받침은 '로'
        ("P201", "을/를", "P201을"),
        ("JB2608-0129", "을/를", "JB2608-0129를"),
        ("주소1", "으로/로", "주소1로"),  # 일·칠·팔은 ㄹ 받침
        ("라인7", "이/가", "라인7이"),
        ("코드3", "으로/로", "코드3으로"),
        ("R", "을/를", "R을"),
        # 영어 컬럼 이름은 소리로 어림한다
        ("dept_name", "으로/로", "dept_name으로"),
        ("line", "으로/로", "line으로"),
        ("level", "으로/로", "level로"),
        ("group", "이/가", "group이"),
        ("order_number", "이/가", "order_number가"),
        ("emp_no", "으로/로", "emp_no로"),
    ],
)
def test_josa_follows_final_consonant(word, pair, expected):
    assert josa(word, pair) == expected


def object_type(code, name, table, *props):
    return {
        "itemKind": "OBJECT_TYPE",
        "payload": {
            "code": code,
            "name": name,
            "objectMapping": {"resourceKey": table},
            "properties": [{"code": c, "name": n} for c, n in props],
        },
        "evidence": {"reason": "원천 표의 한 행을 객체 하나로 본다."},
    }


def proposals():
    return [
        object_type(
            "WORK_ORDER", "작업지시", "mes.work_order", ("so_ref", "수주 참조 번호")
        ),
        object_type("SALES_ORDER", "수주", "erp.sales_order", ("so_no", "수주 번호")),
        object_type(
            "HANDOVER_NOTE",
            "교대 인수인계 메모",
            "mes.handover_note",
            ("content", "메모 원문"),
        ),
        {
            "itemKind": "LINK_RULE",
            "payload": {
                "code": "WORK_ORDER_SO_REF_TO_SALES_ORDER",
                "linkType": {
                    "code": "WORK_ORDER_SO_REF_TO_SALES_ORDER",
                    "name": "WORK_ORDER → SALES_ORDER",
                    "description": "WORK_ORDER가 SALES_ORDER를 가리킨다. 개수 관계는 데이터에서 관찰한 값.",
                    "sourceTypeCode": "WORK_ORDER",
                    "targetTypeCode": "SALES_ORDER",
                },
                "conditions": [
                    {
                        "sourceProperty": "WORK_ORDER.so_ref",
                        "targetProperty": "SALES_ORDER.so_no",
                    }
                ],
            },
            "evidence": {
                "title": "연결 규칙 WORK_ORDER_SO_REF_TO_SALES_ORDER",
                "reason": "값이 이어진다.",
            },
        },
        {
            "itemKind": "LINK_TYPE",
            "payload": {
                "code": "ABOUT_WORK_ORDER",
                "name": "HANDOVER_NOTE → WORK_ORDER",
                "description": "HANDOVER_NOTE의 글이 WORK_ORDER를 언급한다. 원천 키가 아니라 글에서 찾은 연결이다.",
                "sourceTypeCode": "HANDOVER_NOTE",
                "targetTypeCode": "WORK_ORDER",
            },
            "evidence": {
                "candidateId": "LT-DOC-ABOUT_WORK_ORDER",
                "reason": "HANDOVER_NOTE 11건 · WORK_ORDER 기재 13건 · 일치 13건",
            },
        },
        {
            "itemKind": "DOCUMENT_LINK",
            "payload": {
                "documentId": "HN-260820-N",
                "mentionText": "JB2608-0129",
                "targetObjectRef": "mes.work_order:JB2608-0129",
            },
            "evidence": {
                "reason": "'JB2608-0129'를 원천 키 또는 설비 이름과 맞춰 mes.work_order:JB2608-0129에 잇는다.",
                "items": [{"locator": "mes.handover_note.content"}],
            },
        },
    ]


def test_link_rule_link_type_and_document_link_get_korean_titles():
    out = readable(proposals())
    rule, link_type, document = out[3], out[4], out[5]

    assert rule["payload"]["name"] == "작업지시 → 수주 (수주 참조 번호)"
    assert rule["evidence"]["title"] == rule["payload"]["name"]
    # 연결 규칙 이유는 무엇으로 무엇을 찾는지와 승인 효과를 문장으로 잇는다(모르는 조각은 그대로)
    assert rule["evidence"]["reason"] == (
        "작업지시의 수주 참조 번호로 수주를 찾습니다. 값이 이어진다."
        " 승인하면 작업지시와 수주가 이어져 탐색 화면과 Agent에서 함께 찾을 수 있습니다."
    )
    assert rule["payload"]["description"] == (
        "연결 기준: 작업지시 수주 참조 번호 = 수주 수주 번호"
    )
    assert rule["payload"]["linkType"]["name"] == "작업지시 → 수주"
    assert rule["payload"]["linkType"]["description"] == "작업지시에서 수주 참조"
    # 코드는 바꾸지 않는다(백엔드가 코드로 찾는다)
    assert rule["payload"]["code"] == "WORK_ORDER_SO_REF_TO_SALES_ORDER"

    assert link_type["payload"]["name"] == "교대 인수인계 메모의 작업지시 연결"
    assert link_type["payload"]["description"] == (
        "교대 인수인계 메모 본문에 기재된 작업지시와 연결"
    )
    assert link_type["evidence"]["reason"] == (
        "교대 인수인계 메모 11건 · 작업지시 기재 13건 · 일치 13건"
    )

    # 메모 이름이 없으면 문서 번호, 대상 이름이 없으면 종류와 번호로 보인다
    assert document["payload"]["name"] == "HN-260820-N → 작업지시 JB2608-0129"
    assert document["evidence"]["reason"] == (
        "메모에 작업지시 번호 JB2608-0129가 그대로 적혀 있습니다."
        " 승인하면 이 메모와 작업지시 JB2608-0129가 이어져 탐색 화면과 Agent에서 함께 찾을 수 있습니다."
    )


def test_unknown_object_keeps_its_code():
    rule = proposals()[3]
    out = readable([rule])
    assert out[0]["payload"]["name"] == "WORK_ORDER → SALES_ORDER (so_ref)"


def test_object_type_from_source_table_is_not_shown_as_25_percent():
    plain = confidence.build(confidence.object_type(True, False, 0))
    assert plain.total == 0.95
    assert "평가 데이터의 표는 모두 맞음" in plain.items[0].label
    assert confidence.build(confidence.object_type(False, False, 0)).total == 0.85


def test_fingerprint_is_kept_when_only_names_change():
    # 재분석 비교가 이름만 바뀐 것을 '변경'으로 보지 않게 지문은 그대로 둔다
    items = proposals()
    items[3]["evidence"]["fingerprint"] = "before"
    out = readable(items)
    assert out[3]["evidence"]["fingerprint"] == "before"


def test_held_document_link_keeps_reason_and_does_not_pick_first_option():
    items = proposals()
    doc = items[5]
    doc["payload"]["targetObjectRef"] = "mes.equipment:EQ-01"
    doc["payload"]["mentionText"] = "2호기"
    doc["evidence"]["holdRecommended"] = True
    doc["evidence"]["reason"] = (
        "'2호기'는 설비 2대와 맞아 하나로 정할 수 없다. 작성자(작업자 24) 배치 라인 확인 필요."
    )
    doc["options"] = [{"optionNo": 1}, {"optionNo": 2}]
    out = readable(items)[5]
    assert out["payload"]["name"].endswith("후보 2개")
    assert "EQ-01" not in out["payload"]["name"]
    assert out["evidence"]["reason"].startswith(
        "메모의 '2호기'에 맞는 equipment가 2개라 하나로 정할 수 없습니다."
    )
    assert "작성자(작업자 24) 배치 라인 확인 필요" in out["evidence"]["reason"]
    # 원래 이유의 첫 문장은 빼고 단서만 잇는다
    assert "하나로 정할 수 없다." not in out["evidence"]["reason"]
    assert "선택지에서 맞는 대상을 골라 주세요" in out["evidence"]["reason"]


def test_document_link_shows_alias_and_memo_excerpt():
    items = proposals()
    doc = items[5]
    text = "새벽 2시쯤 1라인 3호 프레스 진동이 평소보다 커서 점검 요청함."
    mention = "1라인 3호 프레스"
    start = text.index(mention)
    doc["payload"].update(
        {
            "targetObjectRef": "mes.equipment:P103",
            "mentionText": mention,
            "spanStart": start,
            "spanEnd": start + len(mention),
        }
    )
    doc["evidence"]["originalText"] = text
    doc["evidence"]["confidenceItems"] = [
        {"label": "글의 설비 이름·별명을 설비 목록과 맞춤", "weight": 0.8}
    ]
    doc["evidence"]["documentLabel"] = "8/20 야간 인수인계"
    doc["evidence"]["targetLabels"] = {"mes.equipment:P103": "1라인 3호 프레스"}
    doc["evidence"]["observations"] = [
        {"text": "진동", "assertion": "AFFIRMED"},
        {"text": "누유", "assertion": "NEGATED"},
    ]
    out = readable(items)[5]
    # 문서 번호(HN-…)·코드만 보이지 않게 메모 이름과 대상 이름을 쓴다
    assert out["payload"]["name"] == "8/20 야간 인수인계 → 1라인 3호 프레스 (P103)"
    reason = out["evidence"]["reason"]
    assert reason.startswith(
        "메모의 '1라인 3호 프레스'는 1라인 3호 프레스 (P103)의 이름·별칭과 맞습니다."
    )
    # 메모에 무엇이 적혔는지(부정한 관찰은 빼고), 승인하면 무엇이 되는지
    assert " 메모 내용: “새벽 2시쯤 1라인 3호 프레스 진동이" in reason
    assert "(진동 기록)" in reason and "누유" not in reason
    assert reason.endswith(
        "승인하면 이 메모와 1라인 3호 프레스 (P103)가 이어져 탐색 화면과 Agent에서 함께 찾을 수 있습니다."
    )


def test_lot_number_found_through_another_column_says_so():
    items = proposals()
    doc = items[5]
    doc["payload"].update(
        {"targetObjectRef": "erp.shipment:DL2610-0036", "mentionText": "LT261017-1"}
    )
    out = readable(items)[5]
    assert out["evidence"]["reason"].startswith(
        "메모의 'LT261017-1'은 shipment DL2610-0036의 다른 값과 맞습니다."
    )


def test_long_table_comments_are_shortened_in_titles():
    items = [
        object_type("PICK", "출고 피킹 실적", "wms.pick", ("item_cd", "품목 코드")),
        object_type(
            "ITEM",
            "자재·상품 품목 마스터(원자재, 포장재, OEM 상품)",
            "erp.item",
            ("item_cd", "품목 코드"),
        ),
        {
            "itemKind": "LINK_RULE",
            "payload": {
                "code": "PICK_ITEM_CD_TO_ITEM",
                "linkType": {
                    "code": "X",
                    "sourceTypeCode": "PICK",
                    "targetTypeCode": "ITEM",
                },
                "conditions": [
                    {"sourceProperty": "PICK.item_cd", "targetProperty": "ITEM.item_cd"}
                ],
            },
            "evidence": {"reason": "값이 이어진다."},
        },
    ]
    out = readable(items)
    assert (
        out[2]["payload"]["name"]
        == "출고 피킹 실적 → 자재·상품 품목 마스터 (품목 코드)"
    )
    assert (
        out[1]["payload"]["name"] == "자재·상품 품목 마스터(원자재, 포장재, OEM 상품)"
    )


def test_descriptions_and_reasons_use_object_names_not_table_keys():
    items = proposals()
    items[0]["payload"]["description"] = "원천 mes.work_order의 한 행"
    rule = items[3]
    rule["payload"]["description"] = (
        "비교 방법: so_ref는 숫자만 남김 (예: 'SO-1' → '1')"
    )
    rule["payload"]["linkType"]["description"] = (
        "WORK_ORDER가 SALES_ORDER를 가리킨다 (status = OPEN일 때). "
        "개수 관계는 데이터에서 관찰한 값(1:1). 업무상 한쪽이 여러 건일 수 있어 검토 필요."
    )
    rule["evidence"]["reason"] = "대상 키: erp.sales_order so_no (기본키) · 일치율 100%"
    rule["evidence"]["holdReason"] = "mes.work_order 경유 관계"

    out = readable(items)
    payload, evidence = out[3]["payload"], out[3]["evidence"]

    assert out[0]["payload"]["description"] == (
        "원천 테이블 mes.work_order (1행 = 작업지시 1건)"
    )
    assert payload["description"] == (
        "연결 기준: 작업지시 수주 참조 번호 = 수주 수주 번호 "
        "(비교: 수주 참조 번호는 숫자만 남김 (예: 'SO-1' → '1'))"
    )
    assert payload["linkType"]["description"] == (
        "작업지시에서 수주 참조 · 적용 조건: status = OPEN · "
        "데이터상 1:1, 업무상 1:N 여부 확인 필요"
    )
    # 표 코드(erp.sales_order)는 남지 않고 문장으로 바뀐다
    assert "erp.sales_order" not in evidence["reason"]
    assert evidence["reason"].startswith("작업지시의 수주 참조 번호로 수주를 찾습니다.")
    assert evidence["holdReason"] == "작업지시 경유 관계"


def test_via_table_hold_keeps_its_confidence_cap():
    from app.proposers.confidence import hold_cap

    cap, _ = hold_cap(
        "mes.work_order 경유 관계라 연결 규칙 생성 불가. 관계 유형만 제안"
    )
    assert cap == 0.4


def test_fingerprint_ignores_llm_wording_but_not_content():
    from app.proposers.api_format import fingerprint_payload

    base = {
        "code": "DEPARTMENT",
        "name": "부서",
        "description": "부서 기준 정보",
        "category": "master",
        "properties": [
            {"code": "dept_code", "name": "부서 코드", "description": "부서 식별 코드"}
        ],
    }
    reworded = {
        **base,
        "description": "조직 단위 기준 정보",
        "properties": [
            {
                "code": "dept_code",
                "name": "부서코드",
                "description": "부서를 가리키는 코드",
            }
        ],
    }
    added = {**base, "properties": [*base["properties"], {"code": "manager_emp_no"}]}
    assert fingerprint_payload(base) == fingerprint_payload(reworded)
    assert fingerprint_payload(base) != fingerprint_payload(added)
    assert fingerprint_payload(base) != fingerprint_payload(
        {**base, "category": "history"}
    )


def test_same_column_name_on_both_sides_is_not_renamed_to_one_side():
    from app.proposers.readable import Names, _column_names

    names = Names(
        [
            {
                "itemKind": "OBJECT_TYPE",
                "payload": {
                    "code": "LOT",
                    "name": "재고",
                    "properties": [{"code": "receipt_no", "name": "입고 문서"}],
                },
            },
            {
                "itemKind": "OBJECT_TYPE",
                "payload": {
                    "code": "RCPT",
                    "name": "입고",
                    "properties": [{"code": "receipt_no", "name": "입고 번호"}],
                },
            },
        ]
    )
    payload = {
        "conditions": [
            {"sourceProperty": "LOT.receipt_no", "targetProperty": "RCPT.receipt_no"}
        ]
    }
    # 비교 문구는 어느 쪽인지 알 수 없어 그대로, 대상 키는 대상 속성 이름으로
    assert (
        _column_names(
            "대상 키: 입고 receipt_no (기본키) · receipt_no는 숫자만", payload, names
        )
        == "대상 키: 입고 입고 번호 (기본키) · receipt_no는 숫자만"
    )


def test_partial_match_is_never_rounded_up_to_100_percent():
    from app.proposers.links import _floor_percent

    assert _floor_percent(399 / 400) == "99%"
    assert _floor_percent(1.0) == "100%"


def test_korean_and_upper_case_table_keys_become_object_names():
    from app.proposers.readable import Names, _table_names

    names = Names(
        [
            {
                "itemKind": "OBJECT_TYPE",
                "payload": {
                    "code": "ITEM",
                    "name": "품목",
                    "objectMapping": {"resourceKey": "erp.품목"},
                },
            },
            {
                "itemKind": "OBJECT_TYPE",
                "payload": {
                    "code": "QC",
                    "name": "검사 의뢰",
                    "objectMapping": {"resourceKey": "qms.QC_INSP_HDR"},
                },
            },
        ]
    )
    text = "대상 키: erp.품목 품목코드 · qms.QC_INSP_HDR INSP_NO · erp.품목.품목코드 · erp.품목."
    assert (
        _table_names(text, names)
        == "대상 키: 품목 품목코드 · 검사 의뢰 INSP_NO · erp.품목.품목코드 · 품목."
    )


def test_property_name_prefers_korean_column_over_unit_comment():
    from app.proposers.objects import property_name

    assert property_name("지시수량", "EA") == "지시수량"
    assert property_name("emp_no", "사번") == "사번"
    assert property_name("qty", "수량 (EA)") == "수량"
    assert property_name("receipt_no", None) == "receipt_no"


def test_link_rule_reason_reads_as_sentences():
    items = proposals()
    rule = items[3]
    rule["evidence"]["reason"] = (
        "대상 키: erp.sales_order so_no (유일값) · 비교: so_ref는 숫자만 남김 (예: 'SO-1' → '1')"
        " · 일치율 98% (134/136행 연결, 값 종류 기준 97%) · 불일치 예: 2609970"
    )
    reason = readable(items)[3]["evidence"]["reason"]
    assert reason.startswith("작업지시의 수주 참조 번호로 수주를 찾습니다.")
    assert "수주의 수주 번호는 기본키는 아니지만 값이 겹치지 않는 컬럼입니다." in reason
    assert "비교 방법: 수주 참조 번호는 숫자만 남김" in reason
    assert (
        "작업지시 136행 중 134행(98%)이 수주와 이어집니다(값 종류 기준 97%)." in reason
    )
    assert "이어지지 않은 값 예: 2609970." in reason
    # 시스템 말투(대상 키·일치율·불일치 예)는 남기지 않는다
    for jargon in ("대상 키", "일치율", "불일치 예", " · "):
        assert jargon not in reason


def test_target_name_with_final_consonant_gets_right_particle():
    items = proposals()
    doc = items[5]
    doc["payload"].update(
        {"targetObjectRef": "mes.equipment:CT1", "mentionText": "CT1"}
    )
    doc["evidence"]["targetLabels"] = {"mes.equipment:CT1": "냉각탑"}
    reason = readable(items)[5]["evidence"]["reason"]
    # 조사는 괄호가 아니라 이름(냉각탑)에 맞춘다
    assert "이 메모와 냉각탑 (CT1)이 이어져" in reason


def test_link_rule_reason_handles_self_link_condition_and_masked_values():
    items = proposals()
    rule = items[3]
    rule["payload"]["linkType"]["targetTypeCode"] = rule["payload"]["linkType"][
        "sourceTypeCode"
    ]
    rule["evidence"]["reason"] = (
        "일치율 100% (8행 모두 연결) · 적용 조건: so_ref = A"
        " · 불일치 값은 개인정보라 표시하지 않음"
    )
    reason = readable(items)[3]["evidence"]["reason"]
    assert "승인하면 작업지시끼리 이어져" in reason
    assert "수주 참조 번호가 A인 행에만 적용합니다." in reason
    assert "이어지지 않은 값은 개인정보라 보여 주지 않습니다." in reason
