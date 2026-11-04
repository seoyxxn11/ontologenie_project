package com.axcore.platform.review.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProposalDecisionRequestTests {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void prepareValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "REJECT", "HOLD"})
    void acceptsSupportedDecisionWithoutAnOption(String decision) {
        assertThat(validator.validate(new ProposalDecisionRequest(2, decision, null, "검토 사유")))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1000})
    void acceptsReasonWithinTheDocumentedLength(int length) {
        assertThat(
                        validator.validate(
                                new ProposalDecisionRequest(1, "APPROVE", 1L, "가".repeat(length))))
                .isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidInput(ProposalDecisionRequest request, String field) {
        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(field);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(new ProposalDecisionRequest(null, "APPROVE", null, "사유"), "revision"),
                Arguments.of(new ProposalDecisionRequest(0, "APPROVE", null, "사유"), "revision"),
                Arguments.of(new ProposalDecisionRequest(-1, "APPROVE", null, "사유"), "revision"),
                Arguments.of(new ProposalDecisionRequest(1, null, null, "사유"), "decision"),
                Arguments.of(new ProposalDecisionRequest(1, "", null, "사유"), "decision"),
                Arguments.of(new ProposalDecisionRequest(1, "approve", null, "사유"), "decision"),
                Arguments.of(
                        new ProposalDecisionRequest(1, "AUTO_RESOLVED", null, "사유"), "decision"),
                Arguments.of(
                        new ProposalDecisionRequest(1, "APPROVE", 0L, "사유"), "selectedOptionId"),
                Arguments.of(
                        new ProposalDecisionRequest(1, "APPROVE", -1L, "사유"), "selectedOptionId"),
                Arguments.of(new ProposalDecisionRequest(1, "APPROVE", null, null), "reason"),
                Arguments.of(new ProposalDecisionRequest(1, "APPROVE", null, ""), "reason"),
                Arguments.of(new ProposalDecisionRequest(1, "APPROVE", null, " \t\n"), "reason"),
                Arguments.of(
                        new ProposalDecisionRequest(1, "APPROVE", null, "가".repeat(1001)),
                        "reason"));
    }
}
