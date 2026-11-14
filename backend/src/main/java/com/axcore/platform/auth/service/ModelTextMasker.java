package com.axcore.platform.auth.service;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 외부 언어 모델로 보내는 글에서 전화번호·이메일·주민등록번호·'성 + 직함' 이름(김 대리, 홍길동님)을 가린다(S15P31C106-414).
 *
 * <p>AI 서버의 {@code ai/app/llm/privacy.py}(mask_text)와 같은 규칙이다. 그쪽은 분석 경로에만 쓰이고 Agent는 Spring이 모델을
 * 직접 부르므로, Agent 도구 결과에도 같은 가림을 둔다. 정형 값의 금지·민감은 원천 데이터 정책과 {@link DataAccess}가 막고, 이 클래스는 일반 속성 안의
 * 자유 글(인계 메모·작업 내용)에 섞인 값을 가린다. 한계도 같다: 직함 없는 이름("김민수가 교체함")은 찾지 못한다.
 */
public final class ModelTextMasker {

    static final String MASK = "***";

    private static final String SURNAMES =
            "김이박최정강조윤장임한오서신권황안송류홍전고문양손배백허유남심노하곽성차주우구민진나지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국어은편용";
    private static final String TITLES = "대리|과장|차장|부장|주임|반장|조장|사원|팀장|기사|책임|선임|수석|매니저|씨";

    /** 성씨 글자로 시작하지만 사람 이름이 아닌 현장·업무 낱말(정비 기사, 성형 반장, 제조 책임). */
    private static final Set<String> NOT_NAMES =
            Set.of(
                    "정비", "성형", "보전", "품질", "생산", "금형", "설비", "현장", "주간", "야간", "검사", "용접", "공정",
                    "자재", "구매", "영업", "관리", "안전", "조립", "전기", "기계", "도장", "출하", "물류", "공무", "외주",
                    "신입", "선임", "수석", "반장", "차장", "주임", "조장", "기사", "고객", "선생", "선배", "여사", "원장",
                    "소장", "이사", "전무", "국장", "명장", "기장", "공장", "공장장", "지점", "지점장", "주인", "어머", "최종",
                    "공동", "전체", "기술", "연구", "인사", "제조", "원가", "회계", "재무", "개발", "설계", "운영", "영업관리",
                    "기획", "전산", "조달", "공급", "차량", "소재", "원자재", "총무", "경영");

    /** '~님' 앞에 와도 사람 이름이 아닌 말. 사·팀·장으로 끝나는 말(정비사·고객사·정비팀·공장)도 이름으로 보지 않는다. */
    private static final Set<String> NOT_HONORIFIC =
            Set.of("이상", "정상", "고장", "조치", "오류", "주문", "문제", "진행");

    /** 영문·숫자·하이픈 뒤에 이어진 번호는 코드의 일부다(SN01012345678, WO-010-1234-5678). */
    private static final String CODE_BEFORE = "(?<![0-9A-Za-z+-])";

    private enum Kind {
        RRN,
        PHONE,
        EMAIL,
        NAME,
        HONORIFIC
    }

    private record Rule(Kind kind, Pattern pattern) {}

    private static final List<Rule> RULES =
            List.of(
                    // 하이픈 없는 13자리는 바코드·운송장 번호와 구별할 수 없어 가리지 않는다
                    new Rule(Kind.RRN, compile(CODE_BEFORE + "\\d{6}-[1-4]\\d{6}(?!\\d)")),
                    new Rule(
                            Kind.PHONE,
                            compile(
                                    CODE_BEFORE
                                            + "(?:(?:\\+82[-. ]?0?|0)1[016-9][-. ]?\\d{3,4}[-. ]?\\d{4}"
                                            + "|0\\d{1,2}[-.]\\d{3,4}[-.]\\d{4})(?!\\d)")),
                    new Rule(
                            Kind.EMAIL,
                            compile(
                                    "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")),
                    new Rule(
                            Kind.NAME,
                            compile(
                                    "(?<![가-힣])(["
                                            + SURNAMES
                                            + "][가-힣]{0,2})(\\s?)(?=(?:"
                                            + TITLES
                                            + "))")),
                    // '홍길동님께'. 님은 띄우지 않고 이름이 두 글자 이상일 때만 본다('손님'은 사람 이름이 아니다)
                    new Rule(
                            Kind.HONORIFIC,
                            compile("(?<![가-힣])([" + SURNAMES + "][가-힣]{1,2})()(?=님)")));

    private ModelTextMasker() {}

    /** Python {@code re}처럼 \d·\s가 유니코드 숫자·공백(전각 공백 등)도 받게 한다. */
    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS);
    }

    /** 가린 글. null이면 null이다. 가릴 것이 없으면 같은 내용을 돌려준다. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Rule rule : RULES) {
            result = apply(rule, result);
        }
        return result;
    }

    private static String apply(Rule rule, String text) {
        Matcher matcher = rule.pattern().matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (rule.kind() == Kind.NAME || rule.kind() == Kind.HONORIFIC) {
                String name = matcher.group(1);
                boolean notName =
                        NOT_NAMES.contains(name)
                                || (rule.kind() == Kind.HONORIFIC
                                        && (NOT_HONORIFIC.contains(name)
                                                || name.endsWith("사")
                                                || name.endsWith("팀")
                                                || name.endsWith("장")));
                replacement = notName ? matcher.group() : MASK + matcher.group(2);
            } else {
                replacement = MASK;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
