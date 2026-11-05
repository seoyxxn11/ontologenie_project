package com.axcore.platform.ontology.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.domain.ProposalOrigin;
import com.axcore.platform.ontology.dto.ApplyCommand;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@Import({TestcontainersConfiguration.class, ProposalApplyService.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProposalApplyServiceTransactionTests {
    @Autowired ProposalApplyService service;
    @Autowired PlatformTransactionManager transactions;

    private final ApplyCommand command =
            new ApplyCommand(
                    1L, ProposalItemKind.OBJECT_TYPE, ProposalOrigin.AI, Map.of("code", "X"), 1L);

    @Test
    void requiresTheCallerToOwnTheApprovalTransaction() {
        assertThatThrownBy(() -> service.apply(command))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void leavesTheApprovalTransactionRollbackOnlyWhenApplyFails() {
        TransactionTemplate approval = new TransactionTemplate(transactions);

        // 반영 예외를 같은 트랜잭션에서 잡고 이어 가도 커밋되지 않는다
        assertThatThrownBy(
                        () ->
                                approval.executeWithoutResult(
                                        status -> {
                                            assertThatThrownBy(() -> service.apply(command))
                                                    .isInstanceOf(BusinessException.class);
                                            assertThat(status.isRollbackOnly()).isTrue();
                                        }))
                .isInstanceOf(UnexpectedRollbackException.class);
    }
}
