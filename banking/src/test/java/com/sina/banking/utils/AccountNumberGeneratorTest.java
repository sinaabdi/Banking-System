package com.sina.banking.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class AccountNumberGeneratorTest {

    @ParameterizedTest
    @CsvSource({
        "7992739871, 79927398713",
        "1000000000, 10000000009",
        "1234567890, 12345678903"
    })
    void appendsCorrectLuhnCheckDigit(Long seed, Long expected) {
        assertThat(AccountNumberGenerator.withCheckDigit(seed)).isEqualTo(expected);
    }

    @Test
    void checkDigitIsRecoverableFromSeed() {
        Long seed = 555555555L;
        Long result = AccountNumberGenerator.withCheckDigit(seed);

        assertThat(result / 10).isEqualTo(seed);
    }

    @Test
    void differentSeedsNeverProduceTheSameAccountNumber() {
        Long first = AccountNumberGenerator.withCheckDigit(1000000000L);
        Long second = AccountNumberGenerator.withCheckDigit(1000000001L);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void isDeterministicForTheSameSeed() {
        Long seed = 1234567890L;

        Long first = AccountNumberGenerator.withCheckDigit(seed);
        Long second = AccountNumberGenerator.withCheckDigit(seed);

        assertThat(first).isEqualTo(second);
    }
}
