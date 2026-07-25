package io.chatbots.olx.furniture;

import io.chatbots.olx.furniture.FurnitureVariantParser.Variant;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnitureAiAnswerParserTest {

    @Test
    void parsesWidthFromDimsLine() {
        String answer = "MODEL: KALLAX shelving unit with DRÖNA boxes\nDIMS: 77x39x147 cm\nSOURCE: photo+catalog";
        Optional<Variant> v = FurnitureAiAnswerParser.fromAiAnswer(answer);
        assertTrue(v.isPresent());
        assertEquals("W77", v.get().label());
        assertEquals(77, v.get().primaryDimCm());
    }

    @Test
    void unknownYieldsEmpty() {
        assertTrue(FurnitureAiAnswerParser.fromAiAnswer("MODEL: unclear\nDIMS: unknown\nSOURCE: unknown").isEmpty());
    }

    @Test
    void nullOrBlankYieldsEmpty() {
        assertTrue(FurnitureAiAnswerParser.fromAiAnswer(null).isEmpty());
        assertTrue(FurnitureAiAnswerParser.fromAiAnswer("  ").isEmpty());
    }

    @Test
    void fallsBackToWholeAnswerWhenNoDimsLabel() {
        // model named a variant even without a DIMS: line
        assertEquals("W100", FurnitureAiAnswerParser.fromAiAnswer("VITTSJÖ laptop table 100x36x74 cm").get().label());
    }
}
