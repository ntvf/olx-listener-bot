package io.chatbots.olx.score;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.function.Consumer;

/**
 * Orchestrates the "score" feature: scrape the listing, ask Google AI Mode whether it is a good
 * flip on the same regional market, and format a compact Telegram summary.
 */
@Slf4j
public class ScoreService {

    private static final int MAX_DESCRIPTION_CHARS = 400;
    private static final int MAX_ANSWER_CHARS = 3500;

    private final ListingScraper listingScraper;
    private final AiModeSearchService aiModeSearchService;
    private final CaptchaTunnelService captchaTunnelService;

    public ScoreService(ListingScraper listingScraper,
                        AiModeSearchService aiModeSearchService,
                        CaptchaTunnelService captchaTunnelService) {
        this.listingScraper = listingScraper;
        this.aiModeSearchService = aiModeSearchService;
        this.captchaTunnelService = captchaTunnelService;
    }

    /**
     * @param progressNotifier receives intermediate user-facing messages (e.g. the CAPTCHA link)
     * @return the summary message to post to the chat
     */
    public String scoreListing(String listingUrl, Consumer<String> progressNotifier) {
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

        String query = buildQuery(listing);
        log.info("Scoring listing '{}' ({}), query length {}", listing.getTitle(), listing.getPrice(), query.length());

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

    private String buildQuery(ListingInfo listing) {
        StringBuilder query = new StringBuilder();
        query.append("I am a reseller (flipper). Second-hand listing for sale");
        if (listing.getLocation() != null) {
            query.append(" in ").append(listing.getLocation());
        }
        query.append(": \"").append(listing.getTitle()).append("\"");
        if (listing.getPrice() != null) {
            query.append(", asking price ").append(listing.getPrice());
        }
        if (listing.getDescription() != null) {
            query.append(". Details: ").append(StringUtils.abbreviate(listing.getDescription(), MAX_DESCRIPTION_CHARS));
        }
        query.append(". Based on current prices for the same item on the same second-hand market")
                .append(" and online stores in the same region, answer briefly (under 120 words) with exactly:")
                .append(" 1) Is this a good deal to buy for resale?")
                .append(" 2) A realistic resale price point.")
                .append(" 3) How liquid is it — how fast does this item usually sell?");
        return query.toString();
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
        sb.append("💰 Flip check: ").append(listing.getTitle());
        if (listing.getPrice() != null) {
            sb.append(" — ").append(listing.getPrice());
        }
        sb.append("\n\n").append(StringUtils.abbreviate(answer, MAX_ANSWER_CHARS));
        // several scores can queue up behind one another; make clear which ad this belongs to
        sb.append("\n\n🔗 ").append(listing.getUrl());
        return sb.toString();
    }
}
