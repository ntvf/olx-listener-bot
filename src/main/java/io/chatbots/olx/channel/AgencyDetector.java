package io.chatbots.olx.channel;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Classifies a rental listing as owner-posted or agency-posted. All keyword lists and
 * thresholds are deliberately compile-time constants: changes go through git and are
 * locked by unit tests, not runtime config.
 *
 * <p>Text is matched on diacritic-folded <b>stems</b> ("prowizj", "agencj") rather than whole
 * words, so Polish declension ({@code prowizja / prowizję / prowizją}) and ASCII spellings
 * ({@code właściciela / wlasciciela}) are covered by one entry. Owner phrases and negations
 * ("bez agencji", "nie jestem pośrednikiem") are stripped before agency stems are matched.
 */
public final class AgencyDetector {

    public enum Verdict {OWNER, LIKELY_AGENCY, AGENCY}

    /**
     * Owner markers and negations, stripped before any agency stem is matched: "bez prowizji"
     * or "bez agencji" must not trip the "prowizj"/"agencj" stems below. Written with natural
     * diacritics — {@link #fold} normalizes them at load, and the stems absorb declension.
     */
    private static final List<String> OWNER_STEMS = fold(List.of(
            "bez prowizj", "bez posrednik", "bez posrednictw", "bez agencj",
            "nie jestem agencj", "nie jestem posrednik", "nie pobieram prowizj",
            "nie jestem firma", "od wlascicie", "wlascicie prywatn", "mieszkanie prywatn",
            "oferta prywatn", "osoba prywatn", "prywatnie", "bezposrednio od wlascicie",
            "wynajme bezposrednio", "bezposrednio",
            "no commission", "commission free", "direct from owner", "private owner",
            "vid vlasnyka", "власник", "без комісі", "без комис", "собственник", "хозя"
    ));

    /**
     * Near-certain agency tells — any one hit is enough for {@link Verdict#AGENCY}. Includes
     * exclusive-listing wording, brokerage-contract wording, licence/management phrasing, agency
     * legal-entity suffixes, well-known franchise brands, and CRM/export watermarks (agencies
     * bulk-post through CRMs such as ASARI or Galactica Virgo).
     */
    private static final List<String> STRONG_AGENCY_STEMS = fold(List.of(
            "biuro nieruchomosc", "biura nieruchomosc", "umowa posrednictw", "umowe posrednictw",
            "na wylacznosc", "licencj zawodow", "posrednik odpowiedzialny",
            "zarzadzanie najm", "zarzadzania najm", "obsluga najm", "obsluge najm",
            "sp. z o.o", "z o.o.",
            "re/max", "remax", "metrohouse", "emmerson", "home broker", "freedom nieruchomosc",
            "crm", "system crm", "asari", "galactica", "wygenerowano z program",
            "oferta dodana z program", "oferta wygenerowana"
    ));

    /**
     * Weaker agency signals. One hit yields {@link Verdict#LIKELY_AGENCY}; two or more distinct
     * hits escalate to {@link Verdict#AGENCY}, since several soft tells together are as telling
     * as one hard one.
     */
    private static final List<String> WEAK_AGENCY_STEMS = fold(List.of(
            "prowizj", "agencj", "posrednik", "posrednictw", "wynagrodzenie posrednik",
            "honorarium", "oplata posrednik", "oplate posrednik", "oferta biura",
            "zapraszamy do biura", "deweloper",
            "agency fee", "estate agency", "real estate agency", "brokerage", "realtor",
            "агентств", "агенці", "агенц", "ріелтор", "риэлтор", "риелтор", "комиссия агент"
    ));

    /** More distinct listings than this over the last 30 days is an agency, text aside. */
    private static final int MAX_PRIVATE_LISTINGS_30D = 3;

    /**
     * Rotation/churn signal: a genuine owner rarely posts several different flats in a single
     * week. More than this many distinct listings inside 7 days marks an agency even before the
     * 30-day total trips — catches sellers who delete and repost to stay on top of the feed.
     */
    private static final int MAX_PRIVATE_LISTINGS_7D = 2;

    /**
     * Cumulative signal over a long window. Because listings come and go — an agency's live
     * count is always small as old ads expire — we lean on our own retained history: a seller
     * behind more than this many listings across 90 days is an agency whose churn hides it from
     * the shorter windows.
     */
    private static final int MAX_PRIVATE_LISTINGS_90D = 6;

    /**
     * Per-seller listing counts over three rolling windows, drawn from our own feed_offers
     * history (not OLX's live count, which churn keeps low). Counts are the seller's prior
     * listings; the offer being classified is not yet stored, so a fresh owner posting their
     * first flat scores 0/0/0.
     */
    public record SellerActivity(long listingsLast7Days, long listingsLast30Days,
                                 long listingsLast90Days) {
        public static final SellerActivity NONE = new SellerActivity(0, 0, 0);
    }

    private AgencyDetector() {
    }

    public static Verdict classify(String title, String description,
                                   Boolean sellerBusiness, SellerActivity activity) {
        if (activity == null) activity = SellerActivity.NONE;
        if (Boolean.TRUE.equals(sellerBusiness)) return Verdict.AGENCY;
        if (activity.listingsLast30Days() > MAX_PRIVATE_LISTINGS_30D) return Verdict.AGENCY;
        if (activity.listingsLast7Days() > MAX_PRIVATE_LISTINGS_7D) return Verdict.AGENCY;
        if (activity.listingsLast90Days() > MAX_PRIVATE_LISTINGS_90D) return Verdict.AGENCY;

        String text = fold((title == null ? "" : title) + " " + (description == null ? "" : description));
        for (String stem : OWNER_STEMS) {
            text = text.replace(stem, " ");
        }

        for (String stem : STRONG_AGENCY_STEMS) {
            if (text.contains(stem)) return Verdict.AGENCY;
        }

        int weakHits = 0;
        for (String stem : WEAK_AGENCY_STEMS) {
            if (text.contains(stem)) weakHits++;
        }
        if (weakHits >= 2) return Verdict.AGENCY;
        if (weakHits == 1) return Verdict.LIKELY_AGENCY;
        return Verdict.OWNER;
    }

    /**
     * Lower-cases, strips diacritics (NFD + combining-mark removal) and collapses whitespace, so
     * matching is insensitive to accents and ASCII transliteration. Punctuation is kept, letting
     * stems like "z o.o." and "re/max" match.
     */
    static String fold(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                // ł has no canonical decomposition, so NFD leaves it untouched — fold it by hand
                .replace('ł', 'l');
        return n.replaceAll("\\s+", " ").trim();
    }

    private static List<String> fold(List<String> phrases) {
        return phrases.stream().map(AgencyDetector::fold).toList();
    }
}
