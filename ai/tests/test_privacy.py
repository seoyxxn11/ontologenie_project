from app.llm.privacy import mask_text


def test_names_with_titles_phones_and_emails_are_masked():
    masked = mask_text(
        "김 대리에게 연락했고 박민수 반장님 확인 완료. 010-1234-5678, kim@example.com"
    )
    assert masked.text == "*** 대리에게 연락했고 *** 반장님 확인 완료. ***, ***"
    assert sorted(masked.kinds) == ["EMAIL", "NAME", "NAME", "PHONE"]


def test_equipment_and_order_codes_stay():
    text = "새벽 3시쯤 3호기 슬라이드 유압 누유로 정지. RP-26-041 정비 진행 중. 작성 57"
    assert mask_text(text).text == text
    assert mask_text(text).kinds == []
    assert (
        mask_text("정비 기사 호출, 성형 반장 교대").text
        == "정비 기사 호출, 성형 반장 교대"
    )


def test_korean_particles_right_after_numbers_and_emails():
    masked = mask_text(
        "010-1234-5678로 연락, kim@example.com으로 보냄, 900101-1234567은 지움"
    )
    assert "1234" not in masked.text and "example" not in masked.text
    assert sorted(masked.kinds) == ["EMAIL", "PHONE", "RRN"]
