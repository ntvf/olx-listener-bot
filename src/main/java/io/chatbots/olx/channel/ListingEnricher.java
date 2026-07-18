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
                           String description, String imageUrl,
                           String phone, String advertiserName) {
    }

    public Enriched enrich(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                    .timeout(20_000)
                    .get();
            // OLX real-estate feeds surface Otodom listings whose detail pages use a wholly
            // different (Next.js) markup; route them to the Otodom parser instead of the OLX one.
            return isOtodom(url) ? parseOtodom(doc) : parse(doc);
        } catch (Exception e) {
            log.warn("Failed to enrich listing {}: {}", url, e.toString());
            return Enriched.builder().build();
        }
    }

    static boolean isOtodom(String url) {
        return url != null && url.contains("otodom.");
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

    /**
     * Parses an Otodom listing detail page. Otodom is a Next.js app that ships the entire
     * offer as JSON in a {@code <script id="__NEXT_DATA__">} blob, so — unlike OLX — price,
     * area, rooms, location, advertiser type/name and the contact phone are all present in
     * the static HTML. {@code owner.id} is used as the seller id and the advertiser phone is
     * captured for phone-based agency statistics.
     */
    Enriched parseOtodom(Document doc) {
        Enriched.EnrichedBuilder out = Enriched.builder();
        Element script = doc.selectFirst("script#__NEXT_DATA__");
        if (script == null) return out.build();
        try {
            JsonNode ad = objectMapper.readTree(script.data())
                    .path("props").path("pageProps").path("ad");
            if (ad.isMissingNode() || ad.isNull()) return out.build();

            // characteristics carry price / rent (czynsz) / area / room count as flat key-value rows
            for (JsonNode c : ad.path("characteristics")) {
                String key = c.path("key").asText("");
                String value = c.path("value").asText(null);
                switch (key) {
                    case "price" -> out.price(toNumber(value));
                    case "rent" -> out.extraRent(toNumber(value));
                    case "m" -> out.areaM2(toNumber(value));
                    case "rooms_num" -> out.rooms(parseRooms(value));
                    default -> { }
                }
            }
            out.currency("PLN");

            out.location(otodomLocation(ad.path("location")));

            // advertType (AGENCY/PRIVATE) is the authoritative business flag; advertiserType is a fallback
            String advertType = ad.path("advertType").asText("");
            if ("AGENCY".equalsIgnoreCase(advertType)) out.sellerBusiness(true);
            else if ("PRIVATE".equalsIgnoreCase(advertType)) out.sellerBusiness(false);

            JsonNode owner = ad.path("owner");
            String sellerId = owner.path("id").asText(null);
            if (StringUtils.isNotBlank(sellerId)) out.sellerId(sellerId);
            out.advertiserName(StringUtils.trimToNull(owner.path("name").asText(null)));
            out.phone(firstPhone(ad));

            String desc = ad.path("description").asText(null);
            if (StringUtils.isNotBlank(desc)) out.description(Jsoup.parse(desc).text());

            JsonNode images = ad.path("images");
            if (images.isArray() && !images.isEmpty()) {
                out.imageUrl(StringUtils.trimToNull(images.get(0).path("large").asText(null)));
            }
        } catch (Exception e) {
            log.warn("Failed to parse Otodom __NEXT_DATA__", e);
        }
        return out.build();
    }

    /** Prefers the direct contact phone; falls back to the owner/agency phone. */
    private static String firstPhone(JsonNode ad) {
        for (String path : new String[]{"contactDetails", "owner", "agency"}) {
            JsonNode phones = ad.path(path).path("phones");
            if (phones.isArray() && !phones.isEmpty()) {
                String phone = StringUtils.trimToNull(phones.get(0).asText(null));
                if (phone != null) return phone;
            }
        }
        return null;
    }

    /** Builds "City, District" from Otodom's reverse-geocoding ladder, most-specific last. */
    private static String otodomLocation(JsonNode location) {
        String city = null;
        String specific = null;
        for (JsonNode loc : location.path("reverseGeocoding").path("locations")) {
            String level = loc.path("locationLevel").asText("");
            String name = StringUtils.trimToNull(loc.path("name").asText(null));
            if (name == null) continue;
            if (level.startsWith("city")) city = name;
            specific = name; // ladder is ordered broad → narrow
        }
        String result;
        if (city != null && specific != null && !specific.equals(city)) {
            result = city + ", " + specific;
        } else {
            result = city != null ? city : specific;
        }
        return result == null ? null : StringUtils.abbreviate(result, 255);
    }

    private static Integer parseRooms(String value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
