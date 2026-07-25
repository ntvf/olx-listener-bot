package io.chatbots.olx.furniture;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the sub-model <b>variant</b> from a listing's title + description, so the median group
 * can be {@code model+variant} (a BILLY 80 priced against BILLY 80s) instead of one blurred
 * per-model median that mixes a 40&nbsp;cm and a 202&nbsp;cm unit. Deterministic and unit-locked,
 * like {@link FurnitureClassifier}; the ~40% of listings that carry no size here are left for the
 * AI-Mode photo enricher and otherwise fall back to the bare-model median.
 *
 * <p>Signals, in priority order (first hit wins — a listing rarely carries more than one):
 * <ol>
 *   <li><b>Dimensions</b> {@code 80x28x202} / {@code 140x200} / {@code 80 cm} → keyed on the
 *       leading number (the width, the dominant price driver for shelves/wardrobes/desks).</li>
 *   <li><b>Kallax grid</b> {@code 2x4} → mapped to the same width key as its dimensions
 *       ({@code 2}&nbsp;columns&nbsp;=&nbsp;77&nbsp;cm) so "2x4" and "77x147" land in one group.</li>
 *   <li><b>Config counts</b> drawers / seats / shelves → a discrete key ({@code D3}, {@code S2},
 *       {@code H5}) on the axis that actually moves the price for that furniture kind.</li>
 * </ol>
 */
public final class FurnitureVariantParser {

    public enum Source { DIMS, CONFIG, NONE }

    /** The variant key ({@code null} when none found), its width in cm if known, and where it came from. */
    public record Variant(String label, Integer primaryDimCm, Source source) {
        static final Variant NONE = new Variant(null, null, Source.NONE);

        public boolean isPresent() {
            return source != Source.NONE;
        }
    }

    /** width x depth [x height]; 2–3 digits each so a single-digit grid ("2x4") never matches here. */
    private static final Pattern DIMS =
            Pattern.compile("\\b(\\d{2,3})\\s*[x×*/]\\s*(\\d{2,3})(?:\\s*[x×*/]\\s*(\\d{2,3}))?\\b");
    private static final Pattern CM = Pattern.compile("\\b(\\d{2,3})(?:[.,]\\d)?\\s*cm\\b");
    /** Kallax-style AxB with single digits only, so it can't collide with real dimensions. */
    private static final Pattern GRID = Pattern.compile("\\b([1-5])\\s*[x×]\\s*([1-5])\\b");
    private static final Pattern DRAWERS = Pattern.compile("\\b(\\d{1,2})\\s*szuflad");
    private static final Pattern SEATS = Pattern.compile("\\b(\\d)\\s*-?\\s*osobow");
    /** folded "półek/półki/półka" all start "pol"; "polka na książki" etc. */
    private static final Pattern SHELVES = Pattern.compile("\\b(\\d{1,2})\\s*pol");

    /** Kallax column count → cabinet width in cm (rows drive height, columns drive the price-key width). */
    private static final Map<Integer, Integer> GRID_WIDTH = Map.of(1, 42, 2, 77, 3, 112, 4, 147, 5, 182);

    private FurnitureVariantParser() {
    }

    public static Variant parse(String title, String description) {
        String folded = FurnitureClassifier.fold(
                (title == null ? "" : title) + " || " + (description == null ? "" : description));

        Matcher dims = DIMS.matcher(folded);
        if (dims.find()) return width(Integer.parseInt(dims.group(1)), Source.DIMS);

        Matcher cm = CM.matcher(folded);
        if (cm.find()) return width(Integer.parseInt(cm.group(1)), Source.DIMS);

        Matcher grid = GRID.matcher(folded);
        if (grid.find()) {
            int cols = Integer.parseInt(grid.group(1));
            Integer w = GRID_WIDTH.get(cols);
            return w != null ? width(w, Source.CONFIG)
                    : new Variant("G" + grid.group(1) + "x" + grid.group(2), null, Source.CONFIG);
        }
        Matcher drawers = DRAWERS.matcher(folded);
        if (drawers.find()) return new Variant("D" + Integer.parseInt(drawers.group(1)), null, Source.CONFIG);

        Matcher seats = SEATS.matcher(folded);
        if (seats.find()) return new Variant("S" + Integer.parseInt(seats.group(1)), null, Source.CONFIG);

        Matcher shelves = SHELVES.matcher(folded);
        if (shelves.find()) return new Variant("H" + Integer.parseInt(shelves.group(1)), null, Source.CONFIG);

        return Variant.NONE;
    }

    private static Variant width(int cm, Source source) {
        return new Variant("W" + cm, cm, source);
    }
}
