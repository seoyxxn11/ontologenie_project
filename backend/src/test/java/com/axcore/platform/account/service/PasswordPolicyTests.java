package com.axcore.platform.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTests {

    @Test
    void requiresAsciiLettersDigitsAndPunctuationWithinLengthLimits() {
        assertThat(PasswordPolicy.violation("user", "Valid123!")).isEmpty();
        for (String password :
                new String[] {
                    "short1!", "letters!", "1234567!", "Letters123", "가나다라마바사1!", "a1!".repeat(25)
                }) {
            assertThat(PasswordPolicy.violation("user", password)).isPresent();
        }
        assertThat(PasswordPolicy.violation("Valid123!", "Valid123!")).isPresent();
    }

    @Test
    void continuesRejectingEveryRegexLineTerminator() {
        for (String separator : new String[] {"\n", "\r", "\u0085", "\u2028", "\u2029"}) {
            assertThat(PasswordPolicy.violation("user", "Valid123!" + separator)).isPresent();
        }
    }
}
