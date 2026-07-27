package io.chatbots.olx.grabber.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.chatbots.olx.grabber.Offer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses an otodom.pl search results page. Otodom is a Next.js app that ships the whole result set as
 * JSON in a {@code <script id="__NEXT_DATA__">} tag, so a single page fetch yields structured offers
 * (id, title, original creation time, owner type) with no per-listing detail fetch — the same source
 * {@link io.chatbots.olx.channel.ListingEnricher} reads for Otodom detail pages.
 *
 * <p>Freshness keys on {@code createdAtFirst} (the original post time, unchanged when an agency bumps
 * the listing) rather than the bump time, so {@code OlxTelegramBot.isFreshListing} filters re-surfaced
 * old listings. The results are forced to newest-first, since the bot only reads the first page.
 */
@Slf4j
public class OtodomParser extends BaseParser implements Parser {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String OFFER_BASE = "https://www.otodom.pl/pl/oferta/";
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<Offer> parse(String url) {
        String sorted = newestFirst(url);
        try {
            log.info("Fetching url={}", sorted);
            Document doc = Jsoup.connect(sorted)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                    .timeout(15_000)
                    .get();

            return offersFrom(doc);
        } catch (Exception e) {
            log.warn("Error while parsing otodom url:" + sorted, e);
            return List.of();
        }
    }

    List<Offer> offersFrom(Document doc) throws Exception {
        Element nextData = doc.selectFirst("script#__NEXT_DATA__");
        if (nextData == null) {
            log.warn("Otodom page carried no __NEXT_DATA__ (blocked or markup changed)");
            return List.of();
        }
        JsonNode items = findItems(MAPPER.readTree(nextData.data()));
        if (items == null) {
            log.warn("Otodom __NEXT_DATA__ had no listing items");
            return List.of();
        }

        List<Offer> offers = new ArrayList<>();
        for (JsonNode item : items) {
            String slug = item.path("slug").asText(null);
            String name = item.path("title").asText("");
            if (StringUtils.isBlank(slug) || StringUtils.isBlank(name)) continue;
            Instant createdAt = createdAt(item);
            offers.add(Offer.builder()
                    .url(OFFER_BASE + slug)
                    .name(name)
                    .content("")
                    .promoted(item.path("isPromoted").asBoolean(false))
                    .createdAt(createdAt)
                    .updatedAt(createdAt == null ? null : LocalDateTime.ofInstant(createdAt, WARSAW))
                    .build());
        }
        return offers;
    }

    /** Forces the results to newest-first; the bot reads only the first page, so relevance sort would hide new offers. */
    static String newestFirst(String url) {
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        List<String> params = new ArrayList<>();
        params.add("by=LATEST");
        params.add("direction=DESC");
        if (q >= 0) {
            for (String param : url.substring(q + 1).split("&")) {
                if (param.isEmpty()) continue;
                String key = param.split("=", 2)[0];
                if (key.equals("by") || key.equals("direction") || key.equals("page")) continue;
                params.add(param);
            }
        }
        return base + "?" + String.join("&", params);
    }

    /** The original post time (survives bumps), falling back to the displayed Warsaw-local date. */
    private static Instant createdAt(JsonNode item) {
        String firstPosted = item.path("createdAtFirst").asText(null);
        if (StringUtils.isNotBlank(firstPosted)) {
            try {
                return Instant.parse(firstPosted);
            } catch (Exception ignored) {
            }
        }
        String displayed = item.path("dateCreated").asText(null);
        if (StringUtils.isNotBlank(displayed)) {
            try {
                return LocalDateTime.parse(displayed.replace(' ', 'T')).atZone(WARSAW).toInstant();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** The listing array is nested deep under pageProps; find it by shape so a path change doesn't break it. */
    private static JsonNode findItems(JsonNode node) {
        if (node.isObject()) {
            JsonNode items = node.get("items");
            if (items != null && items.isArray() && !items.isEmpty() && items.get(0).has("slug")) {
                return items;
            }
        }
        for (JsonNode child : node) {
            JsonNode found = findItems(child);
            if (found != null) return found;
        }
        return null;
    }
}
