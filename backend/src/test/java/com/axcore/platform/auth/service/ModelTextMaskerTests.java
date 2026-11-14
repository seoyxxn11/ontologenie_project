package com.axcore.platform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AI 서버 ai/tests/test_privacy.py(mask_text)와 같은 사례로 두 쪽 규칙이 같은지 본다(S15P31C106-414). */
class ModelTextMaskerTests {

    @Test
    void masksNamesWithTitlesPhonesAndEmails() {
        assertThat(
                        ModelTextMasker.mask(
                                "김 대리에게 연락했고 박민수 반장님 확인 완료. 010-1234-5678, kim@example.com"))
                .isEqualTo("*** 대리에게 연락했고 *** 반장님 확인 완료. ***, ***");
    }

    @Test
    void keepsEquipmentAndOrderCodesAndJobWords() {
        String text = "새벽 3시쯤 3호기 슬라이드 유압 누유로 정지. RP-26-041 정비 진행 중. 작성 57";
        assertThat(ModelTextMasker.mask(text)).isEqualTo(text);
        assertThat(ModelTextMasker.mask("정비 기사 호출, 성형 반장 교대")).isEqualTo("정비 기사 호출, 성형 반장 교대");
        String codes = "JB2610-0138 2026-10-30T14:00 OD-2610-021 WO-010-1234-5678 SN01012345678";
        assertThat(ModelTextMasker.mask(codes)).isEqualTo(codes);
    }

    @Test
    void masksBeforeKoreanParticles() {
        String masked =
                ModelTextMasker.mask("010-1234-5678로 연락, kim@example.com으로 보냄, 900101-1234567은 지움");
        assertThat(masked).doesNotContain("1234").doesNotContain("example");
    }

    @Test
    void masksPhoneFormatsAndHonorificNames() {
        String masked =
                ModelTextMasker.mask(
                        "010.1234.5678 / 010 1234 5678 / +82-10-1234-5678 / 900101-1234567 / "
                                + "홍길동님께 전달, 김철수 책임·이영희 선임·박 매니저 확인");
        for (String secret : new String[] {"1234", "5678", "900101", "홍길동", "김철수", "이영희", "박 "}) {
            assertThat(masked).doesNotContain(secret);
        }
        assertThat(masked).contains("님께 전달").contains("책임").contains("매니저 확인");
    }

    @ParameterizedTest
    @ValueSource(strings = {"8801234567890", "9001011234567", "운송장 6012345678901"})
    void keepsThirteenDigitNumbersWithoutHyphen(String code) {
        assertThat(ModelTextMasker.mask(code)).isEqualTo(code);
    }

    @Test
    void honorificAfterJobWordsIsNotAName() {
        String text = "정비사님 호출, 고객사님 요청, 정비팀님 확인, 고장님 아님, 이상님 없음";
        assertThat(ModelTextMasker.mask(text)).isEqualTo(text);
        assertThat(ModelTextMasker.mask("홍길동님께, 길동님 확인")).isEqualTo("***님께, ***님 확인");
    }

    @Test
    void departmentWordsBeforeTitlesAreNotNames() {
        String text = "제조 책임 확인, 원가 매니저 보고, 회계 책임, 설계 선임, 운영 매니저, 개발 책임";
        assertThat(ModelTextMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void masksInsideJsonToolResultsWithoutBreakingIt() {
        String json = "{\"note\":\"김 반장 확인, 010-1234-5678\",\"code\":\"P103\"}";
        assertThat(ModelTextMasker.mask(json))
                .isEqualTo("{\"note\":\"*** 반장 확인, ***\",\"code\":\"P103\"}");
        assertThat(ModelTextMasker.mask(null)).isNull();
    }

    @Test
    void unicodeSpacesCountAsSpacesLikePython() {
        // Python re의 \s처럼 전각·줄바꿈 없는 공백도 이름과 직함 사이 공백으로 본다
        assertThat(ModelTextMasker.mask("김\u3000대리 확인")).isEqualTo("***\u3000대리 확인");
        assertThat(ModelTextMasker.mask("김\u00a0반장")).isEqualTo("***\u00a0반장");
    }
}
