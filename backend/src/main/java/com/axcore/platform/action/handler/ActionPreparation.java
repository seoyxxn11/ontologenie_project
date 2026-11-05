package com.axcore.platform.action.handler;

import com.axcore.platform.audit.domain.ChangeEntry;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/** Handler가 업무 조건을 검사한 결과. */
public sealed interface ActionPreparation {

    /** 업무 조건이 맞지 않아 실행하지 않는다. reason은 화면·Agent에 그대로 보여 준다. */
    record Blocked(String reason) implements ActionPreparation {}

    /**
     * 실행할 수 있다. result와 changes는 실행 기록에 남고, write는 실행 기록 ID를 받아 실제 행을 저장한다. 모두 같은 트랜잭션에서 실행된다. 실행
     * 기록은 한 번 남기면 고치지 않으므로, 결과에 들어갈 ID는 write 전에 미리 정해 둔다.
     */
    record Ready(Map<String, Object> result, List<ChangeEntry> changes, LongConsumer write)
            implements ActionPreparation {}

    static ActionPreparation blocked(String reason) {
        return new Blocked(reason);
    }
}
