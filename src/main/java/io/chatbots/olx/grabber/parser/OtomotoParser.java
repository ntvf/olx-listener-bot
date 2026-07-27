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
 * Parses an otomoto.pl search results page. Otomoto is a Next.js app that embeds its results in the
 * {@code __NEXT_DATA__} tag, but — unlike Otodom — behind a urql/GraphQL cache: the {@code advertSearch}
 * payload is a stringified JSON blob under a query-hash key, so we locate and re-parse that string
 * rather than walk a fixed path. One page fetch yields structured offers (id, title, creation time)
 * with no per-listing detail fetch.
 *
 * <p>The price ceiling for a "cheap cars" channel lives in the caller's search URL
 * ({@code search[filter_float_price:to]}); this parser stays domain-agnostic. Freshness keys on the
 * node's {@code createdAt}, and the results are forced to newest-first since the bot reads only the
 * first page. Dedup keys on the {@code OLX_ID<token>} the URL carries — see
 * {@link io.chatbots.olx.grabber.OfferKey}.
 */
@Slf4j
public class OtomotoParser extends BaseParser implements Parser {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // otomoto's newest-first order, URL-encoded as the site ships it (search[order]=created_at_first:desc)
    private static final String ORDER_PARAM = "search%5Border%5D=created_at_first%3Adesc";

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
            log.warn("Error while parsing otomoto url:" + sorted, e);
            return List.of();
        }
    }

    List<Offer> offersFrom(Document doc) throws Exception {
        Element nextData = doc.selectFirst("script#__NEXT_DATA__");
        if (nextData == null) {
            log.warn("Otomoto page carried no __NEXT_DATA__ (blocked or markup changed)");
            return List.of();
        }
        JsonNode edges = findEdges(MAPPER.readTree(nextData.data()));
        if (edges == null) {
            log.warn("Otomoto __NEXT_DATA__ had no advertSearch edges");
            return List.of();
        }

        List<Offer> offers = new ArrayList<>();
        for (JsonNode edge : edges) {
            JsonNode node = edge.path("node");
            String url = node.path("url").asText(null);
            String name = node.path("title").asText("");
            if (StringUtils.isBlank(url) || StringUtils.isBlank(name)) continue;
            Instant createdAt = parseInstant(node.path("createdAt").asText(null));
            offers.add(Offer.builder()
                    .url(url)
                    .name(name)
                    .content(node.path("shortDescription").asText(""))
                    .promoted(node.path("isPremiumTopAd").asBoolean(false))
                    .createdAt(createdAt)
                    .updatedAt(createdAt == null ? null : LocalDateTime.ofInstant(createdAt, WARSAW))
                    .build());
        }
        return offers;
    }

    /** Forces newest-first; the bot reads only the first page, so any other order would hide new offers. */
    static String newestFirst(String url) {
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        List<String> params = new ArrayList<>();
        params.add(ORDER_PARAM);
        if (q >= 0) {
            for (String param : url.substring(q + 1).split("&")) {
                if (param.isEmpty()) continue;
                if (param.startsWith("search%5Border%5D=") || param.startsWith("search[order]=")) continue;
                if (param.startsWith("page=")) continue;
                params.add(param);
            }
        }
        return base + "?" + String.join("&", params);
    }

    /** The results live as a stringified JSON blob in the urql cache; find and re-parse it by shape. */
    private static JsonNode findEdges(JsonNode node) throws Exception {
        if (node.isTextual()) {
            String raw = node.textValue();
            if (raw.contains("\"advertSearch\"") && raw.contains("\"edges\"")) {
                JsonNode edges = MAPPER.readTree(raw).path("advertSearch").path("edges");
                if (edges.isArray()) return edges;
            }
            return null;
        }
        for (JsonNode child : node) {
            JsonNode found = findEdges(child);
            if (found != null) return found;
        }
        return null;
    }

    private static Instant parseInstant(String iso) {
        if (StringUtils.isBlank(iso)) return null;
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }
}
