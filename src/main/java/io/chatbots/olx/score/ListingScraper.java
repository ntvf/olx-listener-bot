package io.chatbots.olx.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Fetches a single listing page (OLX & co) and extracts the data needed to score a deal.
 * Prefers JSON-LD product metadata, falls back to OpenGraph tags and site-specific selectors.
 */
@Slf4j
public class ListingScraper {

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ListingInfo scrape(String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(20_000)
                .get();

        ListingInfo.ListingInfoBuilder info = ListingInfo.builder().url(url);

        String title = null;
        String price = null;
        String description = null;
        String location = null;

        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode node = objectMapper.readTree(script.data());
                JsonNode product = findProductNode(node);
                if (product == null) continue;
                title = StringUtils.defaultIfBlank(product.path("name").asText(null), title);
                description = StringUtils.defaultIfBlank(product.path("description").asText(null), description);
                JsonNode offers = product.path("offers");
                if (offers.isArray() && !offers.isEmpty()) offers = offers.get(0);
                String amount = offers.path("price").asText(null);
                String currency = offers.path("priceCurrency").asText("");
                if (StringUtils.isNotBlank(amount)) {
                    price = StringUtils.strip(amount + " " + currency);
                }
                JsonNode area = offers.path("areaServed");
                if (area.isTextual()) location = area.asText();
            } catch (Exception e) {
                log.debug("Failed to parse JSON-LD block on {}", url, e);
            }
        }

        if (title == null) title = metaContent(doc, "meta[property=og:title]");
        if (title == null) title = doc.title();
        if (description == null) description = metaContent(doc, "meta[property=og:description]");
        if (description == null) description = metaContent(doc, "meta[name=description]");

        // OLX listing page selectors (best effort, markup changes over time)
        if (price == null) price = textOf(doc, "[data-testid=ad-price-container]");
        if (price == null) price = textOf(doc, "[data-testid=aside-price]");
        if (description == null) description = textOf(doc, "[data-cy=ad_description]");
        if (location == null) location = textOf(doc, "[data-testid=map-aside-section]");
        if (location == null) location = textOf(doc, ".css-tq7sz8, [data-testid=location-date]");

        return info.title(clean(title))
                .price(clean(price))
                .description(clean(description))
                .location(meaningfulLocation(clean(location)))
                .build();
    }

    // OLX renders the location client-side; static HTML often only has the section header label
    private String meaningfulLocation(String location) {
        if (location == null || location.length() < 4) return null;
        if (location.matches("(?iu)(місцезнаходження|местоположение|location|lokalizacja|localizare|локация)")) {
            return null;
        }
        return location;
    }

    private JsonNode findProductNode(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode found = findProductNode(item);
                if (found != null) return found;
            }
            return null;
        }
        String type = node.path("@type").isArray()
                ? node.path("@type").get(0).asText("")
                : node.path("@type").asText("");
        if ("Product".equalsIgnoreCase(type) || node.hasNonNull("offers")) return node;
        return findProductNode(node.get("@graph"));
    }

    private String metaContent(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el == null ? null : StringUtils.trimToNull(el.attr("content"));
    }

    private String textOf(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el == null ? null : StringUtils.trimToNull(el.text());
    }

    private String clean(String value) {
        if (value == null) return null;
        return StringUtils.trimToNull(value.replaceAll("\\s+", " "));
    }
}
