package com.axcore.platform.agent.service;

import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.AnswerStage;
import com.axcore.platform.agent.repository.AgentMessageRepository;
import com.axcore.platform.audit.domain.ExecutionStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 처리 중인 답변의 결과를 저장한다. AI 호출은 이 트랜잭션 밖에서 하고 결과만 짧게 저장한다. 저장할 때는 답변을 잠그고 읽어(FOR UPDATE) 5분 정리와 서로
 * 덮어쓰지 않게 한다. 정리로 이미 끝난 답변은 결과로 덮지 않고, 실행한 Action 결과만 덧붙인다(160).
 */
@Service
@RequiredArgsConstructor
public class AgentAnswerService {
    static final String NOT_CONFIGURED_CONTENT = "아직 AI 답변 설정이 되어 있지 않습니다. 질문은 저장되었습니다.";
    static final String NOT_CONFIGURED_REASON = "AI 설정 없음";
    static final String FAILED_CONTENT = "답변을 끝내지 못했습니다. 잠시 후 다시 질문해 주세요.";
    static final String FAILED_REASON = "AI 응답 실패";

    /** 작업을 이미 실행했으면 다시 질문하라고 하지 않는다. 같은 요청을 다시 하면 계획이 겹칠 수 있기 때문이다(160). */
    static final String ACTIONS_DONE_FAILED_CONTENT =
            "요청한 작업은 실행했지만 답변을 끝내지 못했습니다. 아래 실행 결과를 확인해 주세요.";

    private static final String CLARIFY_INTRO = "질문을 정확히 이해하려면 몇 가지를 더 알려 주세요.";

    /** 업무 데이터와 관계없는 질문에 대한 답. AI가 문장을 만들지 않는다(업무 외 질문 가드레일). */
    // 답변은 화면에서 마크다운으로 그린다(150). 문단은 빈 줄로 나누고, 목록 앞뒤에도 빈 줄을 둔다.
    static final String OUT_OF_SCOPE_CONTENT =
            """
            이 Agent는 업무 데이터 조회와 재배정 계획만 도와드려요. 그 밖의 질문은 도와드리기 어려워요.

            설비·작업지시·비가동·자산을 물어봐 주세요. 예: "P103에 연결된 비가동 기록 보여줘\"""";

    /** 인사나 사용법 질문에 대한 답. 할 수 있는 일과 예시를 보여 준다. */
    static final String HELP_CONTENT =
            """
            안녕하세요. 설비·작업지시·자산 같은 업무 데이터를 찾아 드려요. 이렇게 물어보세요.

            - 대상 정보: "P101 상세 정보 알려줘"
            - 연결된 기록: "P103에 연결된 비가동 기록 보여줘"
            - 실행 이력: "P101 11월 실행 이력 보여줘"
            - 재배정 계획: "P103 남은 작업 재배정 계획 만들어줘" (권한이 있을 때)

            설비 코드나 이름을 함께 말하면 더 정확하게 찾아요.""";

    /** 사람 단위 평가·감시 질문에 대한 답(384-④). 조회하지 않고, 기록 단위로 담당자를 찾는 질문은 된다는 것을 예시로 알린다. */
    static final String PERSON_EVALUATION_CONTENT =
            """
            작업자별 실적·불량률·근무 시간이나 사람끼리의 순위·비교처럼 개인을 평가하거나 감시하는 질문에는 답하지 않아요. \
            이 Agent는 설비와 작업 운영을 돕기 위한 것이에요.

            설비·작업지시 단위로 물어봐 주세요. 기록마다 누가 했는지는 확인할 수 있어요. \
            예: "P103 이번 달 불량 기록 보여줘", "JB2610-0138 작업은 누가 했어?\"""";

    private final AgentMessageRepository messages;

    /** 업무가 아닌 범위에 답하는 정해진 문장. 업무 질문에는 정해진 문장이 없다. */
    static String outsideBusinessContent(QuestionScope scope) {
        return switch (scope) {
            case HELP -> HELP_CONTENT;
            case OUT_OF_SCOPE -> OUT_OF_SCOPE_CONTENT;
            case PERSON_EVALUATION -> PERSON_EVALUATION_CONTENT;
            case BUSINESS -> throw new IllegalArgumentException("업무 질문은 정해진 문장으로 답하지 않는다");
        };
    }

    /**
     * 업무가 아닌 질문(사용 안내·업무 외·개인 평가·감시)에 정해진 문장으로 답한다. 조회하지 않으므로 결과가 없고, 이번 답변의 조건(conditions)도 남기지
     * 않는다. answerMeta.scope로 이 답변이 업무 외 안내임을 남겨, 다음 질문은 이 답변을 건너뛰고 그 전 대화(되묻기나 업무 답변)를 이어 간다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void answerOutsideBusiness(long answerId, QuestionScope scope) {
        String content = outsideBusinessContent(scope);
        running(answerId)
                .ifPresent(
                        answer ->
                                answer.succeed(
                                        content,
                                        Map.of(
                                                "syncStatus",
                                                "NONE",
                                                "results",
                                                List.of(),
                                                "actions",
                                                List.of(),
                                                QuestionScope.ANSWER_META_KEY,
                                                scope.name())));
    }

    /** 처리 중 단계를 바꾼다. 화면은 084를 다시 읽어 단계 문구를 보여 준다(정책 문서 3.3). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void moveTo(long answerId, AnswerStage stage) {
        running(answerId).ifPresent(answer -> answer.moveTo(stage));
    }

    /**
     * 도구로 답했다(144). 답변 문장은 AI가 도구 결과로 쓰고, 정보별 결과(results)는 서버가 남긴 조회 기록에서 만든다. 실행한 Action(actions)은
     * 실행 결과에서 만든다(160). 질문 시 동기화는 아직 없어 syncStatus는 NONE이다. unconfirmedLookups는 다시 조회해 성공하지 못한
     * 실패·거절 호출 수이며(449), 0보다 크면 화면이 답변에 빠진 정보가 있을 수 있다고 알린다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void answerWithResults(
            long answerId,
            String content,
            QuestionInterpretation interpretation,
            List<AgentQueryResult> results,
            List<AgentActionOutcome> actions,
            int unconfirmedLookups) {
        messages.findForUpdateById(answerId)
                .ifPresent(
                        answer -> {
                            if (!answer.isRunning()) {
                                keepLateActions(answer, actions);
                                return;
                            }
                            answer.succeed(
                                    content,
                                    Map.of(
                                            "syncStatus", "NONE",
                                            "results", toMaps(results),
                                            "conditions", interpretation.toConditions(),
                                            "actions", actionMaps(actions),
                                            "unconfirmedLookups", unconfirmedLookups));
                        });
    }

    /**
     * 대상·목적·기간이 부족하거나 대상을 객체 하나로 정하지 못하면 되묻는다(143·147). 지금까지 알아낸 조건은 conditions에 남겨 다음 답과 합친다. 정하지
     * 못한 대상은 clarification.targets에 후보와 함께 남긴다. known은 대화에서 이미 다룬 객체(화면 참고 객체 포함)이며
     * clarification.contextObjectIds로 남겨 다음 답에서 먼저 고르게 한다. 화면 참고 객체는 첫 질문에만 오므로 되물어도 잃지 않게 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void answerClarification(
            long answerId,
            QuestionInterpretation interpretation,
            List<AgentTargetResolver.TargetIssue> targetIssues,
            List<AgentReferenceObject> known) {
        running(answerId)
                .ifPresent(
                        answer ->
                                answer.succeed(
                                        clarifyingQuestion(interpretation, targetIssues),
                                        Map.of(
                                                "syncStatus", "NONE",
                                                "results", List.of(),
                                                "conditions", interpretation.toConditions(),
                                                "actions", List.of(),
                                                "clarification",
                                                        clarification(
                                                                interpretation,
                                                                targetIssues,
                                                                known))));
    }

    static Map<String, Object> clarification(
            QuestionInterpretation interpretation,
            List<AgentTargetResolver.TargetIssue> targetIssues,
            List<AgentReferenceObject> known) {
        Map<String, Object> clarification = new LinkedHashMap<>(interpretation.toClarification());
        if (!targetIssues.isEmpty()) {
            clarification.put(
                    "missing",
                    missing(interpretation, targetIssues).stream().map(Enum::name).toList());
            clarification.put(
                    "targets",
                    targetIssues.stream().map(AgentTargetResolver.TargetIssue::toMap).toList());
        }
        if (!known.isEmpty()) {
            clarification.put(
                    "contextObjectIds", known.stream().map(AgentReferenceObject::id).toList());
        }
        return clarification;
    }

    /** 대상을 말했지만 객체로 정하지 못했으면 대상도 되물을 조건이다. */
    private static List<MissingCondition> missing(
            QuestionInterpretation interpretation,
            List<AgentTargetResolver.TargetIssue> targetIssues) {
        List<MissingCondition> missing = new ArrayList<>(interpretation.missing());
        if (!targetIssues.isEmpty() && !missing.contains(MissingCondition.TARGET)) {
            missing.addFirst(MissingCondition.TARGET);
        }
        return missing;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long answerId, String content, String reason) {
        running(answerId).ifPresent(answer -> answer.fail(content, reason));
    }

    /**
     * 도구를 부른 뒤 답변을 끝내지 못했다. 얻은 조회 결과와 이미 실행한 Action은 남긴다(정책 문서 3.2, 160). 계획을 만든 뒤 AI 응답이 실패해도 화면이
     * 그 계획을 보여 주고, 안내 문구도 다시 질문하라고 하지 않아 같은 요청으로 계획이 겹치지 않게 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failWithResults(
            long answerId,
            List<AgentQueryResult> results,
            List<AgentActionOutcome> actions,
            int unconfirmedLookups) {
        messages.findForUpdateById(answerId)
                .ifPresent(
                        answer -> {
                            if (!answer.isRunning()) {
                                keepLateActions(answer, actions);
                                return;
                            }
                            answer.fail(
                                    failedContent(actions),
                                    FAILED_REASON,
                                    Map.of(
                                            "syncStatus",
                                            "NONE",
                                            "results",
                                            toMaps(results),
                                            "actions",
                                            actionMaps(actions),
                                            "unconfirmedLookups",
                                            unconfirmedLookups));
                        });
    }

    /** 실패 안내 문구. 실제로 실행된 Action이 있으면 다시 질문하라고 하지 않는다. */
    static String failedContent(List<AgentActionOutcome> actions) {
        return executedAny(actions) ? ACTIONS_DONE_FAILED_CONTENT : FAILED_CONTENT;
    }

    /**
     * 5분 정리로 먼저 실패 처리된 답변에 뒤늦게 끝난 Action 결과를 덧붙인다(정책 문서 3.4, 160). 만든 계획이 답변에서 사라지지 않게 한다. 실패 상태와
     * 이유는 그대로 두고, 실행된 Action이 있으면 다시 질문하라는 안내만 바꾼다.
     */
    private static void keepLateActions(AgentMessage answer, List<AgentActionOutcome> actions) {
        if (actions.isEmpty()) {
            return;
        }
        answer.attachActions(
                executedAny(actions) ? ACTIONS_DONE_FAILED_CONTENT : answer.getContent(),
                actionMaps(actions));
    }

    private static boolean executedAny(List<AgentActionOutcome> actions) {
        return actions.stream()
                .anyMatch(action -> ExecutionStatus.SUCCEEDED.name().equals(action.status()));
    }

    private static List<Map<String, Object>> toMaps(List<AgentQueryResult> results) {
        return results.stream().map(AgentQueryResult::toMap).toList();
    }

    private static List<Map<String, Object>> actionMaps(List<AgentActionOutcome> actions) {
        return actions.stream().map(AgentActionOutcome::toMap).toList();
    }

    static String clarifyingQuestion(
            QuestionInterpretation interpretation,
            List<AgentTargetResolver.TargetIssue> targetIssues) {
        StringBuilder text = new StringBuilder(CLARIFY_INTRO);
        for (MissingCondition missing : missing(interpretation, targetIssues)) {
            if (missing == MissingCondition.TARGET && !targetIssues.isEmpty()) {
                targetIssues.forEach(issue -> text.append('\n').append(targetLine(issue)));
                continue;
            }
            text.append('\n')
                    .append(
                            switch (missing) {
                                case TARGET -> "- 어떤 설비·작업지시·공정을 말씀하시는지 알려 주세요.";
                                case PURPOSE -> "- 무엇을 알고 싶으신지 알려 주세요. (예: 상태, 비가동 이력)";
                                case PERIOD ->
                                        "- '"
                                                + interpretation.vaguePeriod()
                                                + "'이 언제부터 언제까지인지 알려 주세요. (예: 10월 1일부터 10월 31일까지)";
                            });
        }
        return text.toString();
    }

    private static String targetLine(AgentTargetResolver.TargetIssue issue) {
        if (issue.candidates().isEmpty()) {
            return "- '" + issue.target() + "'에 해당하는 대상을 찾지 못했습니다. 이름이나 코드를 다시 알려 주세요.";
        }
        List<AgentReferenceObject> candidates = issue.candidates();
        return "- '"
                + issue.target()
                + "'에 해당하는 대상이 여러 개입니다: "
                + candidates.stream()
                        .map(candidate -> candidateLabel(candidate, candidates))
                        .collect(Collectors.joining(", "))
                + ". 어느 것인지 알려 주세요.";
    }

    /** 이름·유형이 같은 후보가 또 있으면 구별할 수 있게 객체 번호를 붙인다. 사용자는 "번호 N"으로 고를 수 있다(147). */
    private static String candidateLabel(
            AgentReferenceObject candidate, List<AgentReferenceObject> candidates) {
        boolean sameLooking =
                candidates.stream()
                        .anyMatch(
                                other ->
                                        other.id() != candidate.id()
                                                && Objects.equals(other.name(), candidate.name())
                                                && Objects.equals(
                                                        other.typeName(), candidate.typeName()));
        StringBuilder label = new StringBuilder(String.valueOf(candidate.name()));
        if (candidate.typeName() != null || sameLooking) {
            label.append('(');
            if (candidate.typeName() != null) {
                label.append(candidate.typeName());
            }
            if (sameLooking) {
                label.append(candidate.typeName() != null ? ", " : "")
                        .append("번호 ")
                        .append(candidate.id());
            }
            label.append(')');
        }
        return label.toString();
    }

    private Optional<AgentMessage> running(long answerId) {
        return messages.findForUpdateById(answerId).filter(AgentMessage::isRunning);
    }
}
