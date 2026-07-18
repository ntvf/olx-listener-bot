package io.chatbots.olx.channel;

import io.chatbots.olx.channel.AgencyDetector.SellerActivity;
import io.chatbots.olx.channel.AgencyDetector.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgencyDetectorTest {

    /** listings(last7Days, last30Days, last90Days) — a seller with no meaningful tenure yet. */
    private static SellerActivity listings(long last7, long last30, long last90) {
        return new SellerActivity(last7, last30, last90, Duration.ZERO, last90);
    }

    /** A seller we have tracked for {@code months} while they posted {@code total} prior listings. */
    private static SellerActivity tenured(int months, long total) {
        return new SellerActivity(0, 0, 0, Duration.ofDays(months * 30L), total);
    }

    // --- seller / activity signals -------------------------------------------------

    @Test
    void businessSellerIsAgency() {
        assertEquals(Verdict.AGENCY,
                AgencyDetector.classify("Mieszkanie 2 pokoje", "Ładne mieszkanie", true, SellerActivity.NONE));
    }

    @Test
    void manyListingsOver30dIsAgencyEvenWhenClaimingPrivate() {
        assertEquals(Verdict.AGENCY,
                AgencyDetector.classify("Wynajmę od właściciela", "bez prowizji", false, listings(0, 8, 8)));
    }

    @Test
    void rotatingListingsWithinAWeekIsAgency() {
        // "private" seller with 3 different flats in 7 days = churn, agency in disguise
        assertEquals(Verdict.AGENCY,
                AgencyDetector.classify("Kawalerka od właściciela", "bez prowizji", false, listings(3, 3, 3)));
    }

    @Test
    void steadyChurnOver90dIsAgencyEvenWhenShortWindowsStayLow() {
        // never more than 2-3 in any 30 days, but 7 distinct flats across 90 days as old ads expire
        assertEquals(Verdict.AGENCY,
                AgencyDetector.classify("Mieszkanie od właściciela", "bez prowizji", false, listings(1, 2, 7)));
    }

    @Test
    void twoListingsInAWeekStaysOwner() {
        assertEquals(Verdict.OWNER,
                AgencyDetector.classify("Kawalerka Mokotów", "Wynajmę bezpośrednio", false, listings(2, 2, 2)));
    }

    // --- negation / owner phrases --------------------------------------------------

    @Test
    void bezProwizjiDoesNotTripProwizjaStem() {
        assertEquals(Verdict.OWNER,
                AgencyDetector.classify("Kawalerka Mokotów", "Wynajmę bez prowizji, bezpośrednio", false, listings(1, 1, 1)));
    }

    @Test
    void bezAgencjiDoesNotTripAgencjaStem() {
        assertEquals(Verdict.OWNER,
                AgencyDetector.classify("Mieszkanie prywatne", "Wynajem bez agencji i bez pośredników", false, SellerActivity.NONE));
    }

    @Test
    void nieJestemPosrednikiemStaysOwner() {
        assertEquals(Verdict.OWNER,
                AgencyDetector.classify("2 pokoje", "Nie jestem pośrednikiem, oferta prywatna", false, SellerActivity.NONE));
    }

    // --- Polish declension / diacritics --------------------------------------------

    @Test
    void declinedProwizjeIsCaught() {
        // "prowizję" (accusative) — whole-word "prowizja" would have missed it
        assertEquals(Verdict.LIKELY_AGENCY,
                AgencyDetector.classify("Mieszkanie Wola", "Pobieramy prowizję za wynajem", false, SellerActivity.NONE));
    }

    @Test
    void declinedAgencjaIsCaught() {
        assertEquals(Verdict.LIKELY_AGENCY,
                AgencyDetector.classify("Kawalerka", "Oferta naszą agencją przygotowana", false, SellerActivity.NONE));
    }

    @Test
    void asciiSpellingWithoutDiacriticsIsCaught() {
        // "prowizji" written without the diacritic-bearing letters still matches
        assertEquals(Verdict.LIKELY_AGENCY,
                AgencyDetector.classify("Mieszkanie", "pobieramy prowizji rownowartosc", false, SellerActivity.NONE));
    }

    // --- strong tells (single hit => AGENCY) ---------------------------------------

    @Test
    void biuroNieruchomosciIsAgency() {
        assertEquals(Verdict.AGENCY,
                AgencyDetector.classify("2 pokoje Wola", "Oferta biura nieruchomości", false, SellerActivity.NONE));
    }

    @Test
    void naWylacznoscIsAgency() {
        assertEquals(Verdict.AGENCY,
                AgencyDetector.classify("Mieszkanie", "Oferta na wyłączność", false, SellerActivity.NONE));
    }

    @Test
    void umowaPosrednictwaIsAgency() {
        assertEquals(Verdict.AGENCY,
                AgencyDetector.classify("Kawalerka", "Podpisujemy umowę pośrednictwa", false, SellerActivity.NONE));
    }

    @Test
    void crmWatermarkIsAgency() {
        assertEquals(Verdict.AGENCY,
                AgencyDetector.classify("Mieszkanie 3 pokoje", "Oferta wygenerowana z systemu CRM", false, SellerActivity.NONE));
    }

    @Test
    void franchiseBrandIsAgency() {
        assertEquals(Verdict.AGENCY,
                AgencyDetector.classify("Mieszkanie", "Zapraszamy, RE/MAX Polska", false, SellerActivity.NONE));
    }

    // --- weak tells (one => LIKELY, two => AGENCY) ---------------------------------

    @Test
    void singleWeakSignalIsLikelyAgency() {
        assertEquals(Verdict.LIKELY_AGENCY,
                AgencyDetector.classify("2 pokoje Wola", "Prowizja 50%", false, SellerActivity.NONE));
    }

    @Test
    void twoWeakSignalsEscalateToAgency() {
        assertEquals(Verdict.AGENCY,
                AgencyDetector.classify("2 pokoje Wola", "Nasza agencja pobiera prowizję", false, SellerActivity.NONE));
    }

    // --- publish policy: precision over recall -------------------------------------

    @Test
    void plainListingWithoutOwnerSignalIsHeldBack() {
        // no agency tell, but no positive owner signal either — not published to a no-commission channel
        assertEquals(Verdict.LIKELY_AGENCY,
                AgencyDetector.classify("Mieszkanie 3 pokoje Ursynów", "Wynajmę mieszkanie od zaraz", false, SellerActivity.NONE));
    }

    @Test
    void explicitOwnerPhrasePublishes() {
        assertEquals(Verdict.OWNER,
                AgencyDetector.classify("Mieszkanie 3 pokoje Ursynów", "Wynajmę od właściciela, od zaraz", false, SellerActivity.NONE));
    }

    @Test
    void longTrackedLowVolumePrivateSellerBecomesOwner() {
        // no keyword, but tracked 6 months with a single prior listing — behaves like an owner
        assertEquals(Verdict.OWNER,
                AgencyDetector.classify("Mieszkanie 2 pokoje", "Wynajmę od zaraz", false, tenured(6, 1)));
    }

    @Test
    void recentKeywordlessPrivateSellerIsHeldBack() {
        // known only a month — too early to trust without a keyword
        assertEquals(Verdict.LIKELY_AGENCY,
                AgencyDetector.classify("Mieszkanie 2 pokoje", "Wynajmę od zaraz", false, tenured(1, 1)));
    }

    @Test
    void longTrackedButManyListingsNotTrusted() {
        // tracked long enough, but a steady stream of listings is not owner behaviour
        assertEquals(Verdict.LIKELY_AGENCY,
                AgencyDetector.classify("Mieszkanie 2 pokoje", "Wynajmę od zaraz", false, tenured(6, 5)));
    }

    @Test
    void nullsAreHeldBack() {
        // an empty listing carries no owner signal, so it is not published
        assertEquals(Verdict.LIKELY_AGENCY, AgencyDetector.classify(null, null, null, null));
    }

    // --- advertiser name (harvested from SPA state / JSON) -------------------------

    @Test
    void agencyLegalSuffixInNameIsAgency() {
        // ad copy is clean, but the harvested advertiser name carries "sp. z o.o." — a hard tell
        assertEquals(Verdict.AGENCY,
                AgencyDetector.classify("Mieszkanie 2 pokoje", "Wynajmę od zaraz",
                        "HORIZON ESTATE sp. z o.o.", false, SellerActivity.NONE));
    }

    @Test
    void biuroNieruchomosciInNameIsAgency() {
        assertEquals(Verdict.AGENCY,
                AgencyDetector.classify("Kawalerka Wola", "Ładne mieszkanie",
                        "Biuro Nieruchomości Kowalski", false, SellerActivity.NONE));
    }

    @Test
    void personalNameDoesNotTripAgency() {
        // a genuine owner's name is just a name — with an owner phrase this still publishes
        assertEquals(Verdict.OWNER,
                AgencyDetector.classify("Mieszkanie 2 pokoje", "Wynajmę od właściciela",
                        "Anna Kowalska", false, SellerActivity.NONE));
    }

    @Test
    void nullAdvertiserNameMatchesLegacyBehaviour() {
        assertEquals(Verdict.LIKELY_AGENCY,
                AgencyDetector.classify("Mieszkanie 3 pokoje", "Wynajmę mieszkanie od zaraz",
                        null, false, SellerActivity.NONE));
    }

    @Test
    void ukrainianOwnerPhraseRecognized() {
        assertEquals(Verdict.OWNER,
                AgencyDetector.classify("Здам квартиру", "Здам від власника, без комісії", false, listings(1, 1, 1)));
    }

    @Test
    void russianAgencyKeywordIsLikelyAgency() {
        assertEquals(Verdict.LIKELY_AGENCY,
                AgencyDetector.classify("Сдам квартиру", "Работает агентство недвижимости", false, SellerActivity.NONE));
    }
}
