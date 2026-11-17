package com.axcore.platform.agent.service;

import com.axcore.platform.agent.domain.AnswerStage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 085로 접수한 질문에 답한다. 커밋 뒤 다른 스레드에서 처리해 AI 호출이 길어도 085 응답을 붙잡지 않는다. 서버가 처리 중에 멈추면 남은 답변은 5분 뒤 정리된다(정책
 * 문서 3.4).
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AgentAnswerProcessor {
    private final AgentAnswerService answers;
    private final QuestionInterpreter interpreter;
    private final AgentResponder responder;
    private final AgentQueryRecorder recorder;
    private final AgentTargetResolver targetResolver;
    private final Clock clock;
    private final TaskExecutor applicationTaskExecutor;
    // 조회 결과의 기준 시점을 구한다(145).
    private final DataAsOfResolver dataAsOfResolver;
    // 답변이 끝난 뒤 오래된 대화 요약을 갱신한다(333).
    private final ConversationSummaries summaries;

    @TransactionalEventListener
    public void onRequested(AgentAnswerRequested event) {
        try {
            applicationTaskExecutor.execute(
                    () -> {
                        answer(event);
                        refreshSummaryLater(event.answerId());
                    });
        } catch (RuntimeException ex) {
            log.error("Agent 답변 {}을 처리 대기열에 넣지 못했습니다. 처리 중으로 남습니다.", event.answerId(), ex);
        }
    }

    /**
     * 답변을 저장한 뒤 오래된 대화 요약을 따로 대기열에 넣는다(333). 같은 작업에서 이어 하면 요약의 AI 호출 동안 실행기 스레드를 붙잡아, 동시 질문이 많을 때
     * 뒤에 들어온 다른 답변이 기다린다. 요약을 못 만들어도 답변에는 영향이 없다.
     */
    private void refreshSummaryLater(long answerId) {
        try {
            applicationTaskExecutor.execute(() -> summaries.refreshAfter(answerId));
        } catch (RuntimeException ex) {
            log.warn("답변 {} 뒤 오래된 대화 요약을 대기열에 넣지 못했습니다. 다음 답변 뒤에 다시 만듭니다.", answerId, ex);
        }
    }

    private void answer(AgentAnswerRequested event) {
        long answerId = event.answerId();
        // 도구를 부르기 시작한 뒤 실패하면, 그때까지 얻은 결과와 실행한 Action을 실패 답변에 남긴다(160).
        AgentQuerySession session = null;
        try {
            if (!interpreter.isConfigured()) {
                answers.fail(
                        answerId,
                        AgentAnswerService.NOT_CONFIGURED_CONTENT,
                        AgentAnswerService.NOT_CONFIGURED_REASON);
                return;
            }
            QuestionInterpretation asked =
                    interpreter.interpret(
                            event.question(),
                            event.context(),
                            event.pending(),
                            event.previous(),
                            event.recentTurns(),
                            summaries.enabled() ? event.olderSummary() : null);
            if (asked.scope() != QuestionScope.BUSINESS) {
                // 업무 외 질문과 사용 안내는 대상을 되묻거나 조회하지 않고 정해진 문장으로 답한다(업무 외 질문 가드레일).
                answers.answerOutsideBusiness(answerId, asked.scope());
                return;
            }
            boolean newQuestionAfterClarification =
                    event.pending() != null && !repliesToClarification(asked, event.pending());
            PendingClarification pending = newQuestionAfterClarification ? null : event.pending();
            // 되묻기에 답하지 않고 새로 물었으면 그 질문만으로 답한다. 이전 답변의 조건과 합치지 않으므로 서버가 예전 답변의 기간 같은 조건을
            // 이어 쓰지 않는다(147). 다만 해석 AI는 최근 대화 원문을 보므로, 원문의 조건을 가져오지 않는 것은 해석 지시에 맡긴다.
            if (pending == null) {
                asked = asked.withRepeatedTargets(event.question(), event.previous());
            }
            QuestionInterpretation interpretation =
                    newQuestionAfterClarification
                            ? asked
                            : continued(asked, pending, event.previous());
            session =
                    new AgentQuerySession(
                            event.asker(), event.questionId(), recorder, clock, dataAsOfResolver);
            List<AgentReferenceObject> known = known(event);
            List<AgentTargetResolver.TargetIssue> targetIssues = List.of();
            // "전체 설비로"처럼 종류 전체로 답하면 정하지 못한 대상은 다시 찾지 않는다.
            List<String> unresolved =
                    pending == null || !asked.kinds().isEmpty()
                            ? List.of()
                            : pending.unresolvedTargets();
            if (!unresolved.isEmpty()) {
                // 되물은 대상만 다시 정하고, 그때 이미 정한 객체는 그대로 둔다(147).
                List<String> answered = asked.targets().isEmpty() ? unresolved : asked.targets();
                AgentTargetResolver.Resolution resolution =
                        targetResolver.resolve(answered, known, session);
                QuestionInterpretation before = pending.previous();
                interpretation =
                        interpretation.withTargets(
                                replaced(before.targets(), unresolved, answered),
                                merged(before.referenceObjects(), resolution.found()));
                targetIssues = resolution.issues();
            } else {
                List<String> toResolve = targetsToResolve(asked, interpretation);
                if (!toResolve.isEmpty()) {
                    AgentTargetResolver.Resolution resolution =
                            targetResolver.resolve(toResolve, known, session);
                    interpretation = interpretation.withReferenceObjects(resolution.found());
                    targetIssues = resolution.issues();
                }
            }
            if (!interpretation.missing().isEmpty() || !targetIssues.isEmpty()) {
                answers.answerClarification(answerId, interpretation, targetIssues, known);
                return;
            }
            answers.moveTo(answerId, AnswerStage.QUERYING);
            session.allowObjects(interpretation.referenceObjectIds());
            // 되묻기에 대한 답이면 무엇을 되물었는지도 알려 "두 번째 거" 같은 답을 질문으로 읽지 않게 한다(147).
            String content =
                    responder.answer(
                            event.question(),
                            interpretation,
                            session,
                            pending == null ? null : pending.askedQuestion());
            // 목록 질문에서 물은 상태와 다른 저장 값으로 찾았으면 답변 끝에 알린다. 답변 AI가 밝히지 않아 서버가 비교한다(360 후속).
            // 앞 질문에서 이어받은 값은 이번 조회와 짝지을 수 없어 이번 질문에서 말한 값만 비교한다. 되묻기에 대한 답은 같은 질문을
            // 마무리하는 것이라 합친 해석의 값이 이번 질문의 값이다.
            List<String> said =
                    pending == null ? asked.propertyValues() : interpretation.propertyValues();
            content =
                    PropertyValueNotice.append(
                            content,
                            interpretation,
                            said,
                            session.filteredValuesByProperty(),
                            session.storedValues());
            session.requireRecorded();
            answers.answerWithResults(
                    answerId,
                    content,
                    interpretation,
                    session.results(),
                    session.actions(),
                    session.unconfirmedLookups());
        } catch (RuntimeException ex) {
            log.error("Agent 답변 {}의 AI 처리가 실패했습니다.", answerId, ex);
            try {
                if (session == null) {
                    answers.fail(
                            answerId,
                            AgentAnswerService.FAILED_CONTENT,
                            AgentAnswerService.FAILED_REASON);
                } else {
                    // 조회 기록을 남기지 못했으면 그 조회 결과는 보여 주지 않는다. 실행한 Action은 실행기가 따로 기록했으므로 남긴다.
                    answers.failWithResults(
                            answerId,
                            session.allRecorded() ? session.results() : List.of(),
                            session.actions(),
                            session.allRecorded() ? session.unconfirmedLookups() : 0);
                }
            } catch (RuntimeException saveEx) {
                log.error("Agent 답변 {}을 실패로 저장하지 못했습니다. 처리 중으로 남습니다.", answerId, saveEx);
            }
        }
    }

    /**
     * 되묻기 다음 말이 그 되묻기에 대한 답인가(147). 무엇을 되물었는지를 함께 본다. 되묻지 않은 목적을 새로(이미 정한 목적과 다르게) 말하면서 대상이나 종류도
     * 말했을 때만 새 질문으로 본다.
     *
     * <ul>
     *   <li>목적을 되물었으면 답에 목적이 있어도 답이다. 예: "지난달 거 보여줘"에 목적·대상을 되물었고 "PR-03 비가동 이력"이라고 답함
     *   <li>대상을 되물었으면 같은 목적을 다시 말해도 답이다. 예: "PR-06 비가동 이력 보여줘"
     *   <li>대상을 되물었는데 "현재 진행 중인 작업지시를 보여줘"처럼 다른 목적을 물으면 새 질문이다.
     * </ul>
     */
    static boolean repliesToClarification(
            QuestionInterpretation asked, PendingClarification pending) {
        String settledPurpose = pending.previous().purpose();
        boolean namesSubject = !asked.targets().isEmpty() || !asked.kinds().isEmpty();
        boolean newPurpose =
                asked.purpose() != null
                        && settledPurpose != null
                        && !samePurpose(asked.purpose(), settledPurpose);
        return !(newPurpose && namesSubject);
    }

    /** AI가 같은 목적을 조금 다르게 쓸 수 있어(예: "비가동 이력"·"최근 비가동 이력"), 띄어쓰기를 빼고 한쪽이 다른 쪽을 품으면 같다고 본다. */
    private static boolean samePurpose(String a, String b) {
        String left = a.replaceAll("\\s+", "");
        String right = b.replaceAll("\\s+", "");
        return left.contains(right) || right.contains(left);
    }

    /** 되묻기에 대한 답이면 되물을 때까지 알아낸 조건과, 후속 질문이면 직전 답변의 조건과 합친다(143·147). */
    private static QuestionInterpretation continued(
            QuestionInterpretation asked,
            PendingClarification pending,
            QuestionInterpretation previous) {
        if (pending != null) {
            return asked.fillFrom(pending.previous());
        }
        if (previous != null) {
            return asked.continueFrom(previous);
        }
        return asked;
    }

    /**
     * 서버가 객체로 찾아야 하는 대상(147). 이번 질문에서 대상을 말했으면 그것을 찾는다. 말하지 않았어도 이어 쓴 대상이 아직 객체로 정해지지 않았으면(예: 되물은 뒤
     * 기간만 답한 경우) 그것을 찾는다.
     */
    static List<String> targetsToResolve(
            QuestionInterpretation asked, QuestionInterpretation interpretation) {
        if (!asked.targets().isEmpty()) {
            return asked.targets();
        }
        return interpretation.referenceObjects().isEmpty() ? interpretation.targets() : List.of();
    }

    /** 이전 대상 이름 중 되물은 것을 이번 답으로 바꾼다. */
    static List<String> replaced(
            List<String> before, List<String> unresolved, List<String> answered) {
        List<String> targets = new ArrayList<>(before);
        targets.removeAll(unresolved);
        answered.stream().filter(target -> !targets.contains(target)).forEach(targets::add);
        return targets;
    }

    static List<AgentReferenceObject> merged(
            List<AgentReferenceObject> kept, List<AgentReferenceObject> found) {
        Map<Long, AgentReferenceObject> merged = new LinkedHashMap<>();
        kept.forEach(object -> merged.putIfAbsent(object.id(), object));
        found.forEach(object -> merged.putIfAbsent(object.id(), object));
        return List.copyOf(merged.values());
    }

    /**
     * 이미 대화에서 다룬 객체. 화면 참고 객체, 이전 답변·되묻기의 대상, 되물을 때 보여 준 후보다. 같은 이름이거나 후보 중 하나면 그것으로 정하고, 되물을 때는 다음
     * 답을 위해 남긴다(147).
     */
    private static List<AgentReferenceObject> known(AgentAnswerRequested event) {
        // 같은 객체가 여러 번 나오면 이름을 아는 것을 남기도록, ID만 아는 화면 참고 객체를 마지막에 넣는다.
        List<AgentReferenceObject> known = new ArrayList<>();
        if (event.pending() != null) {
            known.addAll(event.pending().previous().referenceObjects());
            known.addAll(event.pending().preferred());
        }
        if (event.previous() != null) {
            known.addAll(event.previous().referenceObjects());
        }
        if (event.context().get("referenceObjectIds") instanceof List<?> ids) {
            ids.stream()
                    .filter(Number.class::isInstance)
                    .forEach(
                            id ->
                                    known.add(
                                            AgentReferenceObject.idOnly(
                                                    ((Number) id).longValue())));
        }
        return merged(known, List.of());
    }
}
