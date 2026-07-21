package io.chatbots.olx.furniture;

import java.math.BigDecimal;
import java.text.Normalizer;
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
 *   <li><b>Model-token collisions</b> — a bare model word could match a non-IKEA item
 *       (BILLY→a Schwalbe "Billy Bonkers" bike tyre, PAX→a "pax Romana" game). Collision safety
 *       is handled at the <b>feed</b> level: the channel's one feed is scoped to the OLX
 *       <i>meble</i> (furniture) category with {@code q=ikea}, so bike tyres and games never enter.
 *       Matching stays <b>whole-word</b> (token-based) so a substring like {@code malma} or
 *       {@code billyboy} never trips a model — but the title need <i>not</i> repeat the word
 *       "ikea": most real units name only the model ("Komoda HEMNES", "Rama łóżka Malm"), and
 *       requiring the brand word in the title threw those genuine units away.</li>
 * </ul>
 */
public final class FurnitureClassifier {

    /** Listings under this ask are parts/junk regardless of wording — a whole unit costs more. */
    static final BigDecimal PRICE_FLOOR = BigDecimal.valueOf(60);

    /** Used-IKEA ranges by Warsaw supply; folded to accent-free, lower-case tokens at load. */
    private static final Set<String> MODELS = foldSet(List.of(
            "MALM", "PAX", "KALLAX", "BILLY", "HEMNES", "POÄNG", "BESTÅ", "BRIMNES", "STUVA",
            "HAUGA", "LACK", "EKET", "IVAR", "METOD", "SONGESAND", "NORDLI", "TROFAST",
            "GODMORGON", "FRIHETEN", "EKTORP", "HAVSTA", "IDANÄS", "TONSTAD", "VIHALS",
            "SMÅSTAD", "PLATSA", "GALANT", "ALEX", "MICKE", "LINNMON", "BEKANT", "MARKUS",
            "JÄRVFJÄLLET", "STRANDMON", "LANDSKRONA", "KIVIK", "VIMLE", "SÖDERHAMN", "FINNALA",
            "GRÖNLID", "KLIPPAN", "NORDEN", "INGATORP", "EKEDALEN", "LISABO", "MELLTORP",
            "SKOGSTA", "BJURSTA", "RANARP", "HEKTAR",
            // second wave, added from live prod feed misses (2026-07-21)
            "OXBERG", "HOLMSUND", "KLEPPSTAD", "VITTSJÖ", "LERHAMN", "NORDMYRA", "INGOLF",
            "BRUSALI", "PAHL", "VIMUND", "ÄMMARYD", "SKRUVBY", "LIATORP", "LIXHULT", "SUNDVIK",
            "TARVA", "NEIDEN", "KULLEN", "RAKKESTAD", "SLATTUM", "GLADOM", "KRAGSTA", "LISTERBY",
            "SPIKSMED", "BAGGEBO", "VALLENTUNA", "REGISSÖR", "GURSKEN", "FLISAT", "SNIGLAR",
            "TÄRENDÖ", "LIDHULT", "UPPLAND", "NOCKEBY", "BACKSÄLEN", "HAVSTEN", "SKOGSBO",
            "IDÅSEN", "BROR", "HEMBACKA", "NORDVIKEN", "INGATORP", "STENSELE", "MÖRBYLÅNGA"));

    /**
     * Standalone accessories / spares that are (almost) never a whole unit — flagged wherever they
     * appear in the title. Matched as folded substrings (stems), catching declension: {@code pokrow}
     * catches {@code pokrowiec/pokrowce}.
     */
    private static final List<String> PART_STEMS_ANYWHERE = foldList(List.of(
            "gałk", "uchwyt", "zawias", "prowadnic", "pokrow", "zaślepk", "zaslepk",
            "nakładk", "nakladk", "akcesori", "materac"));

    /**
     * Component nouns that <b>double</b> as whole-unit features — a dresser has drawers, a shelving
     * unit has shelves, a wardrobe has doors. These are a part only when they <b>lead</b> the title:
     * a component sale names the component first ("Szuflada MALM", "Drzwi do szafy PAX"), a whole
     * unit names the furniture first ("Komoda MALM 6 szuflad", "Szafa PAX z drzwiami").
     */
    private static final List<String> PART_STEMS_LEADING = foldList(List.of(
            "drzwi", "szuflad", "półk", "polk", "front", "blat",
            "noga", "nogi", "nóżk", "nozk", "kosz", "część", "czesc"));

    private FurnitureClassifier() {
    }

    /**
     * The model to bucket this listing under, or {@code null} when the title carries no known
     * IKEA model as a whole word. When {@code pinned} is given (a single-model feed) it must
     * match; a blank {@code pinned} detects the first known model token in the title. No "ikea"
     * word is required — the feed is already scoped to the IKEA furniture category.
     */
    public static String modelFor(String title, String pinned) {
        Set<String> tokens = tokens(title);
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
        if (PART_STEMS_ANYWHERE.stream().anyMatch(folded::contains)) return true;
        String lead = leadNoun(folded);
        return lead != null && PART_STEMS_LEADING.stream().anyMatch(lead::startsWith);
    }

    /** First purely-alphabetic token of the folded title, skipping leading quantities like "2x". */
    private static String leadNoun(String folded) {
        for (String token : folded.split("[^a-z0-9]+")) {
            if (!token.isEmpty() && token.chars().allMatch(Character::isLetter)) return token;
        }
        return null;
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
}
