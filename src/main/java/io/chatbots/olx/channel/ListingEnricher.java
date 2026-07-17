package io.chatbots.olx.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches an OLX listing page and extracts what channel scoring needs: numeric price,
 * area, rooms, czynsz, seller identity/type, image. Best effort — any missing field
 * stays null, the offer is still stored.
 */
@Slf4j
public class ListingEnricher {

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";

    private static final Pattern AREA = Pattern.compile("(?iu)powierzchnia:?\\s*([\\d\\s]+[.,]?\\d*)\\s*m");
    private static final Pattern ROOMS = Pattern.compile("(?iu)liczba pokoi:?\\s*(kawalerka|\\d+)");
    private static final Pattern EXTRA_RENT = Pattern.compile("(?iu)czynsz\\s*\\(dodatkowo\\):?\\s*([\\d\\s]+[.,]?\\d*)\\s*zł");
    private static final Pattern GENERIC_AREA = Pattern.compile("(?u)(\\d{2,3})\\s*m²");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Builder
    public record Enriched(BigDecimal price, String currency, BigDecimal extraRent,
                           BigDecimal areaM2, Integer rooms, String location,
                           String sellerId, Boolean sellerBusiness,
                           String description, String imageUrl) {
    }

    public Enriched enrich(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                    .timeout(20_000)
                    .get();
            return parse(doc);
        } catch (Exception e) {
            log.warn("Failed to enrich listing {}: {}", url, e.toString());
            return Enriched.builder().build();
        }
    }

    Enriched parse(Document doc) {
        Enriched.EnrichedBuilder out = Enriched.builder();

        parseJsonLd(doc, out);

        String params = paramsText(doc);
        String description = textOf(doc, "[data-cy=ad_description]");
        out.description(description);

        Matcher m = AREA.matcher(params);
        BigDecimal area = m.find() ? toNumber(m.group(1)) : null;
        if (area == null && description != null) {
            Matcher g = GENERIC_AREA.matcher(description);
            if (g.find()) area = toNumber(g.group(1));
        }
        out.areaM2(area);

        m = ROOMS.matcher(params);
        if (m.find()) {
            out.rooms("kawalerka".equalsIgnoreCase(m.group(1)) ? 1 : Integer.parseInt(m.group(1)));
        }

        m = EXTRA_RENT.matcher(params);
        if (m.find()) out.extraRent(toNumber(m.group(1)));

        String paramsLower = params.toLowerCase(Locale.ROOT);
        if (paramsLower.contains("firmowe") || paramsLower.contains("business")) {
            out.sellerBusiness(true);
        } else if (paramsLower.contains("prywatne") || paramsLower.contains("private")) {
            out.sellerBusiness(false);
        }

        out.sellerId(sellerId(doc));
        out.imageUrl(metaContent(doc, "meta[property=og:image]"));

        // location: current markup carries the district in JSON-LD (offers.areaServed.name);
        // parseJsonLd already set it. Older selector-based location, when present, wins.
        String location = textOf(doc, "[data-testid=map-aside-section]");
        if (location == null) location = textOf(doc, "[data-testid=location-date]");
        if (location != null) out.location(StringUtils.abbreviate(location, 255));

        return out.build();
    }

    private void parseJsonLd(Document doc, Enriched.EnrichedBuilder out) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode node = objectMapper.readTree(script.data());
                JsonNode offers = node.path("offers");
                if (offers.isArray() && !offers.isEmpty()) offers = offers.get(0);
                String area = offers.path("areaServed").path("name").asText(null);
                if (StringUtils.isNotBlank(area)) out.location(StringUtils.abbreviate(area, 255));
                String amount = offers.path("price").asText(null);
                if (StringUtils.isNotBlank(amount)) {
                    out.price(toNumber(amount));
                    out.currency(StringUtils.trimToNull(offers.path("priceCurrency").asText("")));
                    return;
                }
            } catch (Exception e) {
                log.debug("Bad JSON-LD block", e);
            }
        }
        // fallback: visible price container, e.g. "3 200 zł"
        String raw = textOf(doc, "[data-testid=ad-price-container]");
        if (raw != null) {
            Matcher m = Pattern.compile("([\\d\\s]+[.,]?\\d*)").matcher(raw);
            if (m.find()) out.price(toNumber(m.group(1)));
            if (raw.contains("zł")) out.currency("PLN");
        }
    }

    /** OLX seller profile links look like /oferty/uzytkownik/<slug>/ or /d/uk/list/user/<slug>/ */
    static String sellerIdFromHref(String href) {
        if (href == null) return null;
        Matcher m = Pattern.compile("(?:uzytkownik|user)/([^/?#]+)").matcher(href);
        return m.find() ? m.group(1) : null;
    }

    private String sellerId(Document doc) {
        for (Element a : doc.select("a[href*=uzytkownik], a[href*=/user/]")) {
            String id = sellerIdFromHref(a.attr("href"));
            if (id != null) return id;
        }
        return null;
    }

    private String paramsText(Document doc) {
        Element container = doc.selectFirst("[data-testid=ad-parameters-container]");
        if (container != null) return container.text();
        // markup fallback: parameters render as a <ul> of <li><p> pairs near the description
        StringBuilder sb = new StringBuilder();
        for (Element li : doc.select("ul li p")) {
            sb.append(li.text()).append(' ');
        }
        return sb.toString();
    }

    static BigDecimal toNumber(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("\\s", "").replace(',', '.');
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String metaContent(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el == null ? null : StringUtils.trimToNull(el.attr("content"));
    }

    private String textOf(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el == null ? null : StringUtils.trimToNull(el.text());
    }
}
