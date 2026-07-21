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
    void realUnitIsRecognisedWithoutTheWordIkea() {
        // the recall bug: genuine units that name only the model, not "ikea" — must be detected,
        // since the feed is already scoped to the IKEA furniture category
        assertEquals("HEMNES", FurnitureClassifier.modelFor("Komoda HEMNES jak nowa, biała", null));
        assertEquals("MALM", FurnitureClassifier.modelFor("Rama łóżka Malm 140x200", null));
        assertEquals("BILLY", FurnitureClassifier.modelFor("Regał BILLY/OXBERG czarny", null));
    }

    @Test
    void matchingIsWholeWordNotSubstring() {
        // a substring must not trip a model (word-boundary via tokenisation)
        assertNull(FurnitureClassifier.modelFor("Komoda malmowana rustykalna", null));
        assertNull(FurnitureClassifier.modelFor("Zabawka billyboy dla dzieci", null));
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
    void componentNounIsAPartOnlyWhenItLeadsTheTitle() {
        // whole units that merely mention their drawers/doors/shelves as a feature -> not parts
        assertFalse(FurnitureClassifier.isPart("Komoda MALM 6 szuflad", BigDecimal.valueOf(300)));
        assertFalse(FurnitureClassifier.isPart("Szafa PAX z drzwiami przesuwnymi", BigDecimal.valueOf(600)));
        assertFalse(FurnitureClassifier.isPart("Regał KALLAX z półkami", BigDecimal.valueOf(200)));
        // the same nouns leading the title -> a component sold alone
        assertTrue(FurnitureClassifier.isPart("Szuflada do komody MALM", BigDecimal.valueOf(80)));
        assertTrue(FurnitureClassifier.isPart("Drzwi przesuwne do szafy PAX", BigDecimal.valueOf(450)));
        // a leading quantity is skipped, so the real lead noun is still checked
        assertTrue(FurnitureClassifier.isPart("2 szuflady MALM", BigDecimal.valueOf(90)));
    }

    @Test
    void subFloorPriceIsTreatedAsPart() {
        assertTrue(FurnitureClassifier.isPart("Szafka MALM Ikea", BigDecimal.valueOf(50)));
        assertFalse(FurnitureClassifier.isPart("Szafka MALM Ikea", BigDecimal.valueOf(200)));
    }
}
