package io.chatbots.olx.furniture;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Buckets a used-IKEA listing by model and screens out anything that would poison a
 * median-per-model. Compile-time dictionaries locked by unit tests, like {@code AgencyDetector}:
 * an IKEA range list changes a few times a year and belongs in git, not runtime config.
 *
 * <p>Two research-driven safeguards (see the volume study):
 * <ul>
 *   <li><b>Parts blocklist</b> — a model name covers the whole unit <i>and</i> its doors/knobs/
 *       drawers/covers, so a naive median over-flags ~30% (a 50&nbsp;zł PAX door looks like an
 *       87%-off wardrobe). Matched on <b>stems</b> so Polish declension is covered by one entry
 *       (e.g. {@code pokrow} catches {@code pokrowiec / pokrowce}).</li>
 *   <li><b>Model-token collisions</b> — a bare model word matches non-IKEA items
 *       (BILLY→a Schwalbe "Billy Bonkers" bike tyre, PAX→a "pax Romana" game). A model is only
 *       accepted when it appears as a whole word <i>and</i> the title also carries an "ikea"
 *       token, so the deal channel stays IKEA-only.</li>
 * </ul>
 */
public final class FurnitureClassifier {

    /** Listings under this ask are parts/junk regardless of wording — a whole unit costs more. */
    static final BigDecimal PRICE_FLOOR = BigDecimal.valueOf(60);

    /** Top used-IKEA ranges by Warsaw supply; folded to accent-free, lower-case tokens at load. */
    private static final Set<String> MODELS = foldSet(List.of(
            "MALM", "PAX", "KALLAX", "BILLY", "HEMNES", "POÄNG", "BESTÅ", "BRIMNES", "STUVA",
            "HAUGA", "LACK", "EKET", "IVAR", "METOD", "SONGESAND", "NORDLI", "TROFAST",
            "GODMORGON", "FRIHETEN", "EKTORP", "HAVSTA", "IDANÄS", "TONSTAD", "VIHALS",
            "SMÅSTAD", "PLATSA", "GALANT", "ALEX", "MICKE", "LINNMON", "BEKANT", "MARKUS",
            "JÄRVFJÄLLET", "STRANDMON", "LANDSKRONA", "KIVIK", "VIMLE", "SÖDERHAMN", "FINNALA",
            "GRÖNLID", "KLIPPAN", "NORDEN", "INGATORP", "EKEDALEN", "LISABO", "MELLTORP",
            "SKOGSTA", "BJURSTA", "RANARP", "HEKTAR"));

    /** Token that must be present for a model match — kills BILLY/PAX-style non-IKEA collisions. */
    private static final String BRAND_TOKEN = "ikea";

    /**
     * Parts / accessories / spares. A model name covers these too, so they must be excluded from
     * the whole-unit median. Matched as folded substrings (stems), catching declension.
     */
    private static final List<String> PART_STEMS = foldList(List.of(
            "drzwi", "gałk", "szuflad", "półk", "polk", "materac", "front", "blat",
            "noga", "nogi", "nóżk", "nozk", "uchwyt", "zawias", "prowadnic", "pokrow",
            "część", "czesc", "akcesori", "nakładk", "nakladk", "zaślepk", "zaslepk", "kosz"));

    private FurnitureClassifier() {
    }

    /**
     * The model to bucket this listing under, or {@code null} when it is not a recognisable
     * IKEA unit. When {@code pinned} is given (the feed searches one model) it must match; a
     * blank {@code pinned} falls back to detecting the first known model token in the title.
     */
    public static String modelFor(String title, String pinned) {
        Set<String> tokens = tokens(title);
        if (!tokens.contains(BRAND_TOKEN)) return null;
        if (pinned != null && !pinned.isBlank()) {
            String folded = fold(pinned);
            return tokens.contains(folded) ? pinned.toUpperCase(Locale.ROOT) : null;
        }
        for (String token : tokens) {
            if (MODELS.contains(token)) return token.toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * True when the listing is a part/accessory or priced below the floor — kept out of the
     * median and never posted.
     */
    public static boolean isPart(String title, BigDecimal price) {
        if (price != null && price.compareTo(PRICE_FLOOR) < 0) return true;
        String folded = fold(title);
        return PART_STEMS.stream().anyMatch(folded::contains);
    }

    /** Whole-word tokens of the folded title (word-boundary matching without regex per model). */
    private static Set<String> tokens(String title) {
        Set<String> out = new LinkedHashSet<>();
        for (String token : fold(title).split("[^a-z0-9]+")) {
            if (!token.isEmpty()) out.add(token);
        }
        return out;
    }

    /** Lower-cases and strips diacritics; ł has no NFD decomposition, so fold it by hand. */
    static String fold(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('ł', 'l');
    }

    private static Set<String> foldSet(List<String> in) {
        Set<String> out = new LinkedHashSet<>();
        in.forEach(s -> out.add(fold(s)));
        return out;
    }

    private static List<String> foldList(List<String> in) {
        return in.stream().map(FurnitureClassifier::fold).toList();
    }

    /** Exposed for tests: the folded model dictionary. */
    static Set<String> models() {
        return Set.copyOf(MODELS);
    }

    /** Exposed for tests: does the folded title carry any part stem. */
    static boolean titleHasPartStem(String title) {
        String folded = fold(title);
        return PART_STEMS.stream().anyMatch(folded::contains);
    }

    static List<String> partStems() {
        return Arrays.asList(PART_STEMS.toArray(new String[0]));
    }
}
