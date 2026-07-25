package io.chatbots.olx.furniture;

import io.chatbots.olx.furniture.FurnitureVariantParser.Source;
import io.chatbots.olx.furniture.FurnitureVariantParser.Variant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnitureVariantParserTest {

    @Test
    void dimensionsKeyOnWidth() {
        Variant v = FurnitureVariantParser.parse("Rama łóżka Malm 140x200", null);
        assertEquals("W140", v.label());
        assertEquals(140, v.primaryDimCm());
        assertEquals(Source.DIMS, v.source());
    }

    @Test
    void threeAxisDimensionsUseTheLeadingWidth() {
        Variant v = FurnitureVariantParser.parse("Regał Billy 80x28x202", null);
        assertEquals("W80", v.label());
        assertEquals(80, v.primaryDimCm());
    }

    @Test
    void dimensionsFoundInDescriptionNotTitle() {
        Variant v = FurnitureVariantParser.parse("Biała komoda Malm Ikea", "3 szuflady, 80x78 cm, połysk");
        assertEquals("W80", v.label());
    }

    @Test
    void singleCmMeasurementCounts() {
        Variant v = FurnitureVariantParser.parse("Stolik Lack 55 cm", null);
        assertEquals("W55", v.label());
        assertEquals(Source.DIMS, v.source());
    }

    @Test
    void kallaxGridMapsToTheSameWidthAsItsDimensions() {
        // "2x4" (2 columns) and "77x147" must land in one group, not two
        Variant grid = FurnitureVariantParser.parse("IKEA KALLAX 2x4 bez pudełek", null);
        Variant dims = FurnitureVariantParser.parse("Regał Kallax 77x147", null);
        assertEquals("W77", grid.label());
        assertEquals(Source.CONFIG, grid.source());
        assertEquals(grid.label(), dims.label());
    }

    @Test
    void gridIsNotMistakenForDimensionsAndViceVersa() {
        assertEquals("W182", FurnitureVariantParser.parse("Kallax Ikea Regal 5x5", null).label());
        // real dimensions (2-3 digit) are never read as a grid
        assertEquals("W120", FurnitureVariantParser.parse("Biurko BEKANT 120x80", null).label());
    }

    @Test
    void drawerCountVariant() {
        assertEquals("D6", FurnitureVariantParser.parse("Komoda biała 6 szuflad Ikea Malm", null).label());
        assertEquals(Source.CONFIG, FurnitureVariantParser.parse("IKEA EKET 2 szuflady", null).source());
    }

    @Test
    void seatCountVariant() {
        assertEquals("S2", FurnitureVariantParser.parse("Sofa 2-osobowa IKEA EKTORP", null).label());
        assertEquals("S3", FurnitureVariantParser.parse("Kanapa 3 osobowa Ektorp", null).label());
    }

    @Test
    void shelfCountVariant() {
        assertEquals("H5", FurnitureVariantParser.parse("Regał Hemnes biały 5 półek na książki", null).label());
    }

    @Test
    void dimensionsWinOverConfigWhenBothPresent() {
        Variant v = FurnitureVariantParser.parse("Komoda MALM 80x78, 3 szuflady", null);
        assertEquals("W80", v.label());
        assertEquals(Source.DIMS, v.source());
    }

    @Test
    void noSizeSignalIsAbsent() {
        Variant v = FurnitureVariantParser.parse("Fotel uszak IKEA STRANDMON żółty", "stan bardzo dobry");
        assertNull(v.label());
        assertFalse(v.isPresent());
        assertEquals(Source.NONE, v.source());
    }

    @Test
    void handlesNullDescription() {
        assertTrue(FurnitureVariantParser.parse("Stolik kawowy Ikea Lack", null).source() == Source.NONE);
    }
}
