package io.chatbots.olx.furniture;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnitureClassifierTest {

    @Test
    void matchesPinnedModelWithIkeaContext() {
        assertEquals("MALM", FurnitureClassifier.modelFor("Szafka nocna MALM Ikea biała", "MALM"));
    }

    @Test
    void diacriticModelMatchesFoldedTitle() {
        // POÄNG feed, title spelled "poang" -> both fold to the same token
        assertEquals("POÄNG", FurnitureClassifier.modelFor("Fotel Poang Ikea", "POÄNG"));
    }

    @Test
    void brandTokenRequiredKillsModelCollisions() {
        // "Billy" the bike tyre, "pax" the game — no "ikea" token, so not our segment
        assertNull(FurnitureClassifier.modelFor("Opona Schwalbe Billy Bonkers 26 x 2.10", "BILLY"));
        assertNull(FurnitureClassifier.modelFor("anno 117: pax Romana ps5", "PAX"));
    }

    @Test
    void pinnedModelMustAppearInTitle() {
        // feed searches MALM but this is a PAX listing that leaked into results
        assertNull(FurnitureClassifier.modelFor("Ikea PAX szafa 235x150", "MALM"));
    }

    @Test
    void autoDetectsModelWhenFeedModelBlank() {
        assertEquals("HEMNES", FurnitureClassifier.modelFor("Ikea HEMNES komoda szara", ""));
        assertNull(FurnitureClassifier.modelFor("Ikea komoda szara", ""));
    }

    @Test
    void partStemsAreFlaggedIncludingPokrowStemFix() {
        assertTrue(FurnitureClassifier.isPart("Drzwi do szafy PAX Ikea", BigDecimal.valueOf(200)));
        assertTrue(FurnitureClassifier.isPart("Gałki do komody MALM", BigDecimal.valueOf(80)));
        // the 2026-07-20 re-verify: plural "pokrowce" must match the pokrowiec stem
        assertTrue(FurnitureClassifier.isPart("Białe pokrowce na kanapę Ektorp Ikea", BigDecimal.valueOf(120)));
    }

    @Test
    void wholeUnitAboveFloorIsNotAPart() {
        assertFalse(FurnitureClassifier.isPart("Kanapa Ikea EKTORP szara", BigDecimal.valueOf(400)));
    }

    @Test
    void subFloorPriceIsTreatedAsPart() {
        assertTrue(FurnitureClassifier.isPart("Szafka MALM Ikea", BigDecimal.valueOf(50)));
        assertFalse(FurnitureClassifier.isPart("Szafka MALM Ikea", BigDecimal.valueOf(200)));
    }
}
