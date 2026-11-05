package com.axcore.platform.ontology.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.ontology.domain.ApplyErrorCode;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.domain.ProposalOrigin;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProposalApplyServiceTests {

    @Test
    void passesTheCommandToTheApplierOfItsKind() {
        ApplyResult expected =
                new ApplyResult("object_type", 10L, null, null, Map.of("created", 1), null);
        ProposalApplyService service =
                new ProposalApplyService(
                        List.of(
                                applier(ProposalItemKind.OBJECT_TYPE, expected),
                                applier(
                                        ProposalItemKind.LINK_TYPE,
                                        new ApplyResult(
                                                "link_type", 20L, null, null, null, null))));

        ApplyResult result = service.apply(command(ProposalItemKind.OBJECT_TYPE));

        assertThat(result).isSameAs(expected);
    }

    @Test
    void rejectsAKindWithoutAnApplier() {
        ProposalApplyService service = new ProposalApplyService(List.of());

        assertThatThrownBy(() -> service.apply(command(ProposalItemKind.LINK_RULE)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ApplyErrorCode.APPLY_KIND_NOT_SUPPORTED);
                            assertThat(e.getDetails()).containsEntry("itemKind", "LINK_RULE");
                        });
    }

    @Test
    void allowsOnlyOneApplierPerKind() {
        ApplyResult result = new ApplyResult("object_type", 1L, null, null, null, null);

        assertThatThrownBy(
                        () ->
                                new ProposalApplyService(
                                        List.of(
                                                applier(ProposalItemKind.OBJECT_TYPE, result),
                                                applier(ProposalItemKind.OBJECT_TYPE, result))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mapsTheProposalOriginToTheDefinitionOrigin() {
        assertThat(ProposalOrigin.AI.definitionOrigin().name()).isEqualTo("AI_PROPOSAL");
        assertThat(ProposalOrigin.MANUAL.definitionOrigin().name()).isEqualTo("MANUAL");
    }

    @Test
    void validatesTheCommandAndResult() {
        assertThatThrownBy(
                        () ->
                                new ApplyCommand(
                                        0L,
                                        ProposalItemKind.OBJECT_TYPE,
                                        ProposalOrigin.AI,
                                        Map.of(),
                                        1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApplyCommand(1L, null, ProposalOrigin.AI, Map.of(), 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApplyResult("object_type", 0L, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        ApplyResult empty = new ApplyResult("object_type", 1L, null, null, null, null);
        assertThat(empty.heldItemIds()).isEmpty();
        assertThat(empty.reloadResourceIds()).isEmpty();
        assertThat(empty.summary()).isEmpty();
        assertThat(empty.changes()).isEmpty();
    }

    private static ApplyCommand command(ProposalItemKind kind) {
        return new ApplyCommand(1L, kind, ProposalOrigin.AI, Map.of("code", "EQUIPMENT"), 3L);
    }

    private static ProposalApplier applier(ProposalItemKind kind, ApplyResult result) {
        return new ProposalApplier() {
            @Override
            public ProposalItemKind kind() {
                return kind;
            }

            @Override
            public ApplyResult apply(ApplyCommand command) {
                return result;
            }
        };
    }
}
