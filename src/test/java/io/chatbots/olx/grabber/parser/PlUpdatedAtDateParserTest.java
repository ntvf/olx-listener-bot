package io.chatbots.olx.grabber.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PlUpdatedAtDateParserTest {

    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 2, 21);

    private static Stream<Arguments> validDateTimeExamples() {
        return Stream.of(
                Arguments.of(
                        "Odświeżono dnia 20 lutego 2026",
                        LocalDateTime.of(2026, 2, 20, 0, 0)
                ),
                Arguments.of(
                        "20 lutego 2026",
                        LocalDateTime.of(2026, 2, 20, 0, 0)
                ),
                Arguments.of(
                        "Odświeżono dzisiaj o 12:33",
                        LocalDateTime.of(2026, 2, 21, 12, 33)
                ),
                Arguments.of(
                        "Dzisiaj o 12:33",
                        LocalDateTime.of(2026, 2, 21, 12, 33)
                ),
                Arguments.of(
                        "Dzisiaj 12:33",
                        LocalDateTime.of(2026, 2, 21, 12, 33)
                ),
                Arguments.of(
                        "Odświeżono dzisiaj o 9:05",
                        LocalDateTime.of(2026, 2, 21, 9, 5)
                ),
                Arguments.of(
                        "Dzisiaj o 00:01",
                        LocalDateTime.of(2026, 2, 21, 0, 1)
                ),
                Arguments.of(
                        "Odświeżono dnia 5 marca 2025",
                        LocalDateTime.of(2025, 3, 5, 0, 0)
                )
        );
    }

    private static Stream<Arguments> invalidExamples() {
        return Stream.of(
                Arguments.of(""),
                Arguments.of("   "),
                Arguments.of("wczoraj o 14:20"),           // not yet supported
                Arguments.of("3 dni temu"),
                Arguments.of("Odświeżono 20 lut 2026"),
                Arguments.of("20 luty 2026"),
                Arguments.of("Odświeżono dnia 32 lutego 2026"),  // invalid day
                Arguments.of("Dzisiaj o 25:00")             // invalid hour
        );
    }

    @ParameterizedTest(name = "{index} → {0} should parse to {1}")
    @MethodSource("validDateTimeExamples")
    @DisplayName("Should correctly parse valid OLX date/time formats")
    void shouldParseValidFormats(String input, LocalDateTime expected) {
        Optional<LocalDateTime> result = PlUpdatedAtDateParser.parseOlxDate(input, FIXED_TODAY);

        assertThat(result.get())
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{index} → invalid input: {0}")
    @MethodSource("invalidExamples")
    @DisplayName("Should throw exception on invalid / unsupported formats")
    void shouldThrowOnInvalidInput(String invalidInput) {
        assertTrue(PlUpdatedAtDateParser.parseOlxDate(invalidInput, FIXED_TODAY).isEmpty());
    }


    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "Dzisiaj o 14:22,          2026-02-21T14:22",
            "Odświeżono dzisiaj o 7:15, 2026-02-21T07:15",
            "20 lutego 2026,            2026-02-20T00:00"
    })
    void shouldParseSimpleCases(String input, String expectedIso) {
        LocalDateTime result = PlUpdatedAtDateParser.parseOlxDate(input, FIXED_TODAY).orElseThrow();
        assertThat(result.toString()).isEqualTo(expectedIso);
    }
}