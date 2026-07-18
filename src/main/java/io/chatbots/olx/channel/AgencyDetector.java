package io.chatbots.olx.channel;

import java.text.Normalizer;
import java.time.Duration;
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
     * The "no middleman" subset of {@link #OWNER_STEMS}: phrases asserting the advertiser <b>is</b>
     * the owner / not an intermediary. Deliberately excludes the commission-waiver phrases
     * ("bez prowizji", "no commission") — those are also used by aparthotel/rent-a-room operators
     * that waive the tenant fee without being the owner, so they read as agency, not owner. Used by
     * {@link #isDirect} to gate a precision-first channel to genuinely owner-direct listings.
     */
    private static final List<String> DIRECT_STEMS = fold(List.of(
            "bez posrednik", "bez posrednictw",
            "od wlascicie", "wlascicie prywatn", "mieszkanie prywatn",
            "oferta prywatn", "osoba prywatn", "prywatnie",
            "bezposrednio od wlascicie", "wynajme bezposrednio", "bezposrednio",
            "direct from owner", "private owner",
            "vid vlasnyka", "власник", "собственник", "хозя"
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
     * How long a keyword-less private seller must have been in our history before their listings
     * are trusted as owner-posted on their own. An agency posts continuously; a genuine owner we
     * have tracked this long while they stayed at a listing or two is behaving like an owner.
     */
    private static final Duration MIN_OWNER_TENURE = Duration.ofDays(150);

    /** ...and "stayed at a listing or two" means no more than this many prior listings, ever. */
    private static final long MAX_TENURE_LISTINGS = 2;

    /**
     * Per-seller listing counts over three rolling windows, drawn from our own feed_offers
     * history (not OLX's live count, which churn keeps low). Counts are the seller's prior
     * listings; the offer being classified is not yet stored, so a fresh owner posting their
     * first flat scores 0/0/0. {@code knownFor} is the span since we first saw this seller
     * ({@link Duration#ZERO} when unseen), and {@code listingsTotal} their prior listing count.
     */
    public record SellerActivity(long listingsLast7Days, long listingsLast30Days,
                                 long listingsLast90Days, Duration knownFor, long listingsTotal) {
        public static final SellerActivity NONE = new SellerActivity(0, 0, 0, Duration.ZERO, 0);
    }

    private AgencyDetector() {
    }

    public static Verdict classify(String title, String description,
                                   Boolean sellerBusiness, SellerActivity activity) {
        return classify(title, description, null, sellerBusiness, activity);
    }

    /**
     * @param advertiserName the seller's/agency's display name, harvested from the listing's SPA
     *                       state (OLX {@code user.name}) or JSON ({@code owner.name}). Matched
     *                       alongside the title and description: an agency's legal name often
     *                       carries a hard tell ("sp. z o.o.", "biuro nieruchomości", a brand) the
     *                       ad copy itself hides. A private seller's personal name trips nothing.
     */
    public static Verdict classify(String title, String description, String advertiserName,
                                   Boolean sellerBusiness, SellerActivity activity) {
        if (activity == null) activity = SellerActivity.NONE;
        if (Boolean.TRUE.equals(sellerBusiness)) return Verdict.AGENCY;
        if (activity.listingsLast30Days() > MAX_PRIVATE_LISTINGS_30D) return Verdict.AGENCY;
        if (activity.listingsLast7Days() > MAX_PRIVATE_LISTINGS_7D) return Verdict.AGENCY;
        if (activity.listingsLast90Days() > MAX_PRIVATE_LISTINGS_90D) return Verdict.AGENCY;

        String text = fold(String.join(" ",
                title == null ? "" : title,
                description == null ? "" : description,
                advertiserName == null ? "" : advertiserName));
        // Presence of an owner phrase is a positive publish signal; capture it before the same
        // stems are stripped so they cannot trip the agency stems ("bez prowizji" vs "prowizj").
        boolean ownerSignal = OWNER_STEMS.stream().anyMatch(text::contains);
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

        // No agency tell found. For a no-commission channel we favour precision over recall:
        // publish only listings that positively read as owner-posted — an explicit owner phrase,
        // or a seller we have tracked as low-volume private for long enough to trust. Everything
        // else is held back as LIKELY_AGENCY (not shown) rather than published on absence of proof.
        boolean establishedPrivate = activity.knownFor().compareTo(MIN_OWNER_TENURE) >= 0
                && activity.listingsTotal() <= MAX_TENURE_LISTINGS;
        if (ownerSignal || establishedPrivate) return Verdict.OWNER;
        return Verdict.LIKELY_AGENCY;
    }

    /**
     * Lower-cases, strips diacritics (NFD + combining-mark removal) and collapses whitespace, so
     * matching is insensitive to accents and ASCII transliteration. Punctuation is kept, letting
     * stems like "z o.o." and "re/max" match.
     */
    /**
     * True when the listing positively advertises itself as owner-direct / no-middleman — an
     * explicit "bezpośrednio", "od właściciela", "prywatnie" (see {@link #DIRECT_STEMS}) in the
     * title, description or advertiser name. Matched on folded stems like {@link #classify}.
     *
     * <p>Independent of the {@link Verdict}: a listing can be {@link Verdict#OWNER} (e.g. a
     * long-tracked low-volume seller) without ever saying it. A precision-first channel publishes
     * only offers that are both OWNER <i>and</i> direct, so a genuine but un-advertised owner is
     * held back rather than a commission-waiving operator being let through.
     */
    public static boolean isDirect(String title, String description, String advertiserName) {
        String text = fold(String.join(" ",
                title == null ? "" : title,
                description == null ? "" : description,
                advertiserName == null ? "" : advertiserName));
        return DIRECT_STEMS.stream().anyMatch(text::contains);
    }

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
