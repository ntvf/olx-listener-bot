package io.chatbots.olx.score;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Orchestrates the "score" feature: scrape the listing, ask Google AI Mode what comparable units
 * currently sell for, and turn that into a buy/pass verdict.
 * <p>
 * The model is deliberately only asked to <em>retrieve comparables</em> — the verdict and the
 * margin are computed here. Asking an LLM "is this a good deal?" while handing it the asking price
 * anchors its estimate on that price and makes it do arithmetic it is unreliable at; asking "what
 * do these sell for?" plays to what search-grounded models are actually good at.
 */
@Slf4j
public class ScoreService {

    private static final int MAX_DESCRIPTION_CHARS = 400;
    private static final int MAX_ANSWER_CHARS = 3500;
    /** below this, the answer is a guess dressed up as data rather than a price signal */
    private static final int MIN_COMPARABLES = 3;

    private final ListingScraper listingScraper;
    private final AiModeSearchService aiModeSearchService;
    private final CaptchaTunnelService captchaTunnelService;
    private final MarketPriceParser marketPriceParser;

    /** Resale headroom over the asking price needed for a BUY, e.g. 0.4 = comps at least 40% above. */
    @Value("${ai.score.buy-margin:0.4}")
    private double buyMargin;

    public ScoreService(ListingScraper listingScraper,
                        AiModeSearchService aiModeSearchService,
                        CaptchaTunnelService captchaTunnelService,
                        MarketPriceParser marketPriceParser) {
        this.listingScraper = listingScraper;
        this.aiModeSearchService = aiModeSearchService;
        this.captchaTunnelService = captchaTunnelService;
        this.marketPriceParser = marketPriceParser;
    }

    /**
     * @param locale           language for the answer — the user's, from their Telegram profile
     * @param progressNotifier receives intermediate user-facing messages (e.g. the CAPTCHA link)
     * @return the summary message to post to the chat
     */
    public String scoreListing(String listingUrl, Locale locale, Consumer<String> progressNotifier) {
        ListingInfo listing;
        try {
            listing = listingScraper.scrape(listingUrl);
        } catch (Exception e) {
            log.warn("Failed to scrape listing {}", listingUrl, e);
            return "❌ Could not read the listing page (" + e.getMessage() + ")";
        }
        if (listing.getTitle() == null) {
            return "❌ Could not extract any data from this listing.";
        }

        String query = buildQuery(listing, locale);
        log.info("Scoring listing '{}' ({}) on {}, answer in {}",
                listing.getTitle(), listing.getPrice(), marketOf(listing.getUrl()), locale.getLanguage());

        String answer;
        try {
            answer = aiModeSearchService.search(query, captcha -> progressNotifier.accept(buildCaptchaMessage(captcha)));
        } catch (Exception e) {
            log.warn("AI Mode search failed for {}", listingUrl, e);
            return "❌ AI search failed: " + e.getMessage();
        } finally {
            captchaTunnelService.close();
        }
        return formatSummary(listing, answer);
    }

    /**
     * Grounds the query on the marketplace the ad lives on (the model cannot infer it — the scraped
     * location is often null) while answering in the user's own language. The asking price is left
     * out on purpose: including it anchors the estimate.
     */
    private String buildQuery(ListingInfo listing, Locale locale) {
        String market = marketOf(listing.getUrl());
        StringBuilder query = new StringBuilder();
        query.append("Second-hand listing on ").append(market);
        if (listing.getLocation() != null) {
            query.append(" in ").append(listing.getLocation());
        }
        query.append(". Title: \"").append(listing.getTitle()).append("\"");
        if (listing.getDescription() != null) {
            query.append(". Condition details: ")
                    .append(StringUtils.abbreviate(listing.getDescription(), MAX_DESCRIPTION_CHARS));
        }
        query.append(". What do comparable used units of this exact item currently sell for on ")
                .append(market).append(" and other second-hand markets in the same region?")
                .append(" Answer in ").append(locale.getDisplayLanguage(Locale.ENGLISH))
                .append(" with exactly these three lines and nothing else:")
                .append(" RANGE: <low>-<high> <currency>")
                .append(" COMPS: <how many comparable listings you found>")
                .append(" NOTE: <one sentence on condition caveats and how fast this item usually sells>.")
                .append(" If you found fewer than ").append(MIN_COMPARABLES)
                .append(" comparable listings, reply \"COMPS: 0\" and omit RANGE rather than estimating.");
        return query.toString();
    }

    /** e.g. "https://www.olx.pl/d/oferta/..." -> "olx.pl" */
    private String marketOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "OLX" : StringUtils.removeStart(host, "www.");
        } catch (Exception e) {
            return "OLX";
        }
    }

    private String buildCaptchaMessage(AiModeSearchService.CaptchaEvent captcha) {
        int minutes = Math.max(1, captcha.getWaitSeconds() / 60);
        String url = captchaTunnelService.openAccessUrl();
        if (StringUtils.isBlank(url)) {
            return "🧩 Google is asking for a CAPTCHA, but I could not open remote access to the browser"
                    + " (no captcha-url configured and the tunnel failed — check the bot logs)."
                    + " Solve it on the server display within " + minutes + " min and I'll continue.";
        }
        return "🧩 Google is asking for a CAPTCHA. Open " + url
                + " , solve it in the browser you'll see there, and I'll continue automatically"
                + " (waiting up to " + minutes + " min).";
    }

    private String formatSummary(ListingInfo listing, String answer) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildVerdict(listing, answer));
        // several scores can queue up behind one another; make clear which ad this belongs to
        sb.append("\n\n🔗 ").append(listing.getUrl());
        return sb.toString();
    }

    private String buildVerdict(ListingInfo listing, String answer) {
        MarketPrice market = marketPriceParser.parse(answer);
        if (market == null) {
            // AI Mode ignored the format; the prose is still worth reading, so don't drop it
            log.info("AI answer did not match the RANGE/COMPS format, posting it raw");
            return "💰 " + listing.getTitle()
                    + priceSuffix(listing)
                    + "\n\n" + StringUtils.abbreviate(answer, MAX_ANSWER_CHARS);
        }

        String header = "💰 " + listing.getTitle() + priceSuffix(listing);
        if (market.getComparables() < MIN_COMPARABLES) {
            return header + "\n\n🤷 Not enough comparable listings to price this one"
                    + note(market);
        }

        String range = formatAmount(market.getLow()) + "–" + formatAmount(market.getHigh())
                + (market.getCurrency() == null ? "" : " " + market.getCurrency());
        Double ask = marketPriceParser.parseListingPrice(listing.getPrice());
        if (ask == null || ask <= 0) {
            return header + "\n\n📊 Comparable units sell for " + range
                    + " (" + market.getComparables() + " comps)"
                    + "\n🤷 No asking price on the ad, so no verdict." + note(market);
        }

        // conservative: judge against the bottom of the range, not the optimistic end
        double margin = (market.getLow() - ask) / ask;
        String verdict = margin >= buyMargin ? "✅ BUY" : "⛔ PASS";
        return header
                + "\n\n" + verdict + " — " + Math.round(margin * 100) + "% headroom vs the low end"
                + "\n📊 Comparable units sell for " + range + " (" + market.getComparables() + " comps)"
                + note(market);
    }

    private String priceSuffix(ListingInfo listing) {
        return listing.getPrice() == null ? "" : " — " + listing.getPrice();
    }

    private String note(MarketPrice market) {
        return market.getNote() == null ? "" : "\nℹ️ " + market.getNote();
    }

    private String formatAmount(double amount) {
        return amount == Math.rint(amount)
                ? String.valueOf((long) amount)
                : String.format(Locale.ROOT, "%.2f", amount);
    }
}
