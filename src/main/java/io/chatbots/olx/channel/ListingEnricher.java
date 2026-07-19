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
import java.time.Instant;
import java.time.OffsetDateTime;
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

    /** When true, OLX-native listings also get their phone via the limited-phones API. */
    private final boolean harvestPhones;

    public ListingEnricher() {
        this(true);
    }

    public ListingEnricher(boolean harvestPhones) {
        this.harvestPhones = harvestPhones;
    }

    @Builder(toBuilder = true)
    public record Enriched(BigDecimal price, String currency, BigDecimal extraRent,
                           BigDecimal areaM2, Integer rooms, String location,
                           String sellerId, Boolean sellerBusiness,
                           String description, String imageUrl,
                           String phone, String advertiserName, Instant createdAt) {
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
            return isOtodom(url) ? parseOtodom(doc) : harvestOlx(parse(doc), doc, url);
        } catch (Exception e) {
            log.warn("Failed to enrich listing {}: {}", url, e.toString());
            return Enriched.builder().build();
        }
    }

    static boolean isOtodom(String url) {
        return url != null && url.contains("otodom.");
    }

    /**
     * Fills in what only the OLX SPA state / phone API carry: the advertiser's real name and
     * business flag from {@code __PRERENDERED_STATE__}, and the phone from the (unauthenticated)
     * {@code /api/v1/offers/{id}/limited-phones/} endpoint. All best-effort — a listing with no
     * exposed phone or a changed endpoint just keeps whatever {@link #parse} already found.
     */
    private Enriched harvestOlx(Enriched base, Document doc, String url) {
        OlxIdentity id = parseOlxPrerendered(doc);
        if (id == null) return base;

        Enriched.EnrichedBuilder b = base.toBuilder();
        if (base.advertiserName() == null && id.sellerName() != null) b.advertiserName(id.sellerName());
        if (base.sellerBusiness() == null && id.business() != null) b.sellerBusiness(id.business());
        if (base.createdAt() == null && id.createdTime() != null) b.createdAt(toInstant(id.createdTime()));
        if (harvestPhones && base.phone() == null && id.hasPhone() && id.offerId() != null) {
            String phone = normalizePhone(fetchOlxPhone(url, id.offerId()));
            if (phone != null) b.phone(phone);
        }
        return b.build();
    }

    /** The advertiser identity OLX ships in its prerendered Redux state, not in the visible markup. */
    record OlxIdentity(String sellerName, Boolean business, String offerId, boolean hasPhone,
                       String createdTime) {
    }

    /**
     * Reads {@code window.__PRERENDERED_STATE__}, a JS string literal holding escaped JSON. The
     * quoted literal is itself valid JSON (its escapes are JSON-compatible), so it decodes in one
     * {@code readTree} pass; the inner JSON is then parsed for {@code ad.ad}.
     */
    OlxIdentity parseOlxPrerendered(Document doc) {
        for (Element script : doc.select("script")) {
            String data = script.data();
            int at = data.indexOf("__PRERENDERED_STATE__");
            if (at < 0) continue;
            try {
                int open = data.indexOf('"', at);
                int close = endOfJsString(data, open);
                if (open < 0 || close < 0) return null;
                String json = objectMapper.readTree(data.substring(open, close + 1)).asText();
                JsonNode ad = objectMapper.readTree(json).path("ad").path("ad");
                if (ad.isMissingNode()) return null;
                String offerId = ad.path("id").asText(null);
                boolean hasPhone = ad.path("contact").path("phone").asBoolean(false);
                Boolean business = ad.has("isBusiness") ? ad.get("isBusiness").asBoolean() : null;
                String name = StringUtils.trimToNull(ad.path("user").path("name").asText(null));
                String createdTime = StringUtils.trimToNull(ad.path("createdTime").asText(null));
                return new OlxIdentity(name, business, offerId, hasPhone, createdTime);
            } catch (Exception e) {
                log.debug("Failed to parse OLX __PRERENDERED_STATE__", e);
                return null;
            }
        }
        return null;
    }

    /** Index of the closing quote of the JS string literal opened at {@code open}, honouring escapes. */
    private static int endOfJsString(String s, int open) {
        if (open < 0) return -1;
        for (int i = open + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') i++;            // skip the escaped char
            else if (c == '"') return i;
        }
        return -1;
    }

    private String fetchOlxPhone(String listingUrl, String offerId) {
        try {
            java.net.URI u = java.net.URI.create(listingUrl);
            String api = u.getScheme() + "://" + u.getHost() + "/api/v1/offers/" + offerId + "/limited-phones/";
            String body = Jsoup.connect(api)
                    .userAgent(USER_AGENT)
                    .header("Accept", "application/json")
                    .ignoreContentType(true)
                    .timeout(15_000)
                    .execute().body();
            JsonNode phones = objectMapper.readTree(body).path("data").path("phones");
            if (phones.isArray() && !phones.isEmpty()) {
                return StringUtils.trimToNull(phones.get(0).asText(null));
            }
        } catch (Exception e) {
            log.debug("No phone for OLX offer {}: {}", offerId, e.toString());
        }
        return null;
    }

    /**
     * Normalizes a phone to a bare digit string with the country code, so the same number matches
     * across sources: Otodom ships "+48570704752", OLX ships "571 310 725". A 9-digit number is
     * assumed Polish and prefixed with 48; leading 00 international prefixes are dropped.
     */
    static String normalizePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("00")) digits = digits.substring(2);
        if (digits.length() == 9) digits = "48" + digits;
        return digits.isEmpty() ? null : digits;
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
            out.createdAt(toInstant(ad.path("createdAt").asText(null)));

            out.location(otodomLocation(ad.path("location")));

            // advertType (AGENCY/PRIVATE) is the authoritative business flag; advertiserType is a fallback
            String advertType = ad.path("advertType").asText("");
            if ("AGENCY".equalsIgnoreCase(advertType)) out.sellerBusiness(true);
            else if ("PRIVATE".equalsIgnoreCase(advertType)) out.sellerBusiness(false);

            JsonNode owner = ad.path("owner");
            String sellerId = owner.path("id").asText(null);
            if (StringUtils.isNotBlank(sellerId)) out.sellerId(sellerId);
            out.advertiserName(StringUtils.trimToNull(owner.path("name").asText(null)));
            out.phone(normalizePhone(firstPhone(ad)));

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

    /**
     * Picks the <b>district</b> from Otodom's reverse-geocoding ladder, so it matches OLX's bare
     * district naming ({@code "Włochy"}, {@code "Mokotów"}). Otodom's ladder goes
     * voivodeship → city → district → residential; the narrowest level is a sub-area
     * ("Nowe Włochy", "Stary Mokotów") that OLX never uses, which would split otherwise-identical
     * districts across the two sources — and now across the district+rooms median segments. Falls
     * back to the city, then the narrowest available, when no district level is present.
     */
    private static String otodomLocation(JsonNode location) {
        String city = null;
        String district = null;
        String narrowest = null;
        for (JsonNode loc : location.path("reverseGeocoding").path("locations")) {
            String level = loc.path("locationLevel").asText("");
            String name = StringUtils.trimToNull(loc.path("name").asText(null));
            if (name == null) continue;
            if (level.startsWith("city")) city = name;
            else if (level.equals("district")) district = name;
            narrowest = name; // ladder is ordered broad → narrow
        }
        String result = district != null ? district : city != null ? city : narrowest;
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

    static Instant toInstant(String iso) {
        if (StringUtils.isBlank(iso)) return null;
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e) {
            return null;
        }
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
