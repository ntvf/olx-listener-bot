package io.chatbots.olx.grabber.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.chatbots.olx.grabber.Offer;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class QA extends BaseParser implements Parser {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LISTING_ID = Pattern.compile("ID([0-9A-Za-z]+)\\.html");

    static String getSortedByLastCreatedUrl(String url) {
        String desiredSort = "search%5Border%5D=created_at:desc";
        int questionMarkIndex = url.indexOf('?');
        if (questionMarkIndex == -1) {
            url = url + "?" + desiredSort;
        } else {

            String base = url.substring(0, questionMarkIndex);
            String query = url.substring(questionMarkIndex + 1);

            String[] params = query.split("&");

            StringBuilder newQuery = new StringBuilder();

            for (String param : params) {
                if (param.startsWith("search%5Border%5D=")) {
                    continue;
                }
                if (!newQuery.isEmpty()) {
                    newQuery.append("&");
                }
                newQuery.append(param);
            }

            if (!newQuery.isEmpty()) {
                newQuery.insert(0, desiredSort + "&");
            } else {
                newQuery.append(desiredSort);
            }

            url = base + "?" + newQuery;
        }
        return url;
    }

    private LocalDateTime getUpdatedAt(Element card) {
        try {
            String raw = card.select("[data-testid=location-date]").text();
            String[] parts = raw.split(" - ");
            // skip [0] (city/location), try remaining segments for a parseable date
            for (int i = 1; i < parts.length; i++) {
                // OLX renders the card time in UTC in the static HTML we scrape ("dzisiaj o 21:40"),
                // and the browser JS localizes it to Warsaw (23:40). Jsoup runs no JS, so we do that
                // conversion ourselves: read the value as UTC and present it in Warsaw local time.
                LocalDateTime dt = PlUpdatedAtDateParser.parseOlxDate(parts[i]);
                if (dt != null) {
                    return LocalDateTime.ofInstant(dt.toInstant(ZoneOffset.UTC), WARSAW);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public List<Offer> parse(String url) {
        try {

            url = getSortedByLastCreatedUrl(url);
            log.info("Fetching url={}", url);

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(15_000)
                    .get();
            Element body = doc.body();
            String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;

            Map<String, Instant> createdAtByListingId = createdTimesById(doc);

            // markup varies by site: pl/bg/ro/pt use a[data-testid=card-title-link],
            // ua only marks cards with data-testid=l-card, uz/kz still use data-cy=ad-card-title
            return body.select("[data-cy=l-card], [data-testid=l-card]").stream()
                    .map(card -> {
                        Element linkElement = card.selectFirst(
                                "a[data-testid=card-title-link], [data-cy=ad-card-title] a[href], [data-testid=ad-card-title] a[href]");
                        String name = linkElement != null ? linkElement.text() : "";
                        if (name.isEmpty()) {
                            name = card.select("[data-cy=ad-card-title] h4").text();
                        }

                        String link = linkElement != null ? linkElement.attr("href") : "";

                        if (link.isEmpty()) {
                            Element anyLink = card.selectFirst("a[href]");
                            link = anyLink != null ? anyLink.attr("href") : "";
                        }

                        if (link.startsWith("/")) {
                            link = baseUrl.substring(0, baseUrl.indexOf("/", 8)) + link;
                        }

                        val updatedAt = getUpdatedAt(card);
                        return Offer.builder()
                                .url(cleanUrlFromQueryParams(link))
                                .content("")
                                .name(name)
                                .updatedAt(updatedAt)
                                .createdAt(createdAtByListingId.get(listingId(link)))
                                .promoted(StringUtils.isNotBlank(link) && Strings.CI.contains(link, "promoted"))
                                .build();
                    })
                    .filter(it -> !it.isPromoted())
                    .filter(it -> StringUtils.isNotBlank(it.getName()))
                    .filter(it -> StringUtils.isNotBlank(it.getUrl()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Error while parsing url:" + url, e);
            return Collections.emptyList();
        }
    }

    Map<String, Instant> createdTimesById(Document doc) {
        Map<String, Instant> createdAtByListingId = new HashMap<>();
        for (Element script : doc.select("script")) {
            String data = script.data();
            int stateStart = data.indexOf("__PRERENDERED_STATE__");
            if (stateStart < 0) continue;
            try {
                int literalStart = data.indexOf('"', stateStart);
                int literalEnd = endOfJsStringLiteral(data, literalStart);
                if (literalStart < 0 || literalEnd < 0) return createdAtByListingId;
                String stateJson = MAPPER.readTree(data.substring(literalStart, literalEnd + 1)).asText();
                JsonNode ads = MAPPER.readTree(stateJson).path("listing").path("listing").path("ads");
                for (JsonNode ad : ads) {
                    String listingId = listingId(ad.path("url").asText(null));
                    Instant createdAt = parseInstant(ad.path("createdTime").asText(null));
                    if (listingId != null && createdAt != null) createdAtByListingId.put(listingId, createdAt);
                }
            } catch (Exception e) {
                log.debug("No usable __PRERENDERED_STATE__ ads on list page", e);
            }
            return createdAtByListingId;
        }
        return createdAtByListingId;
    }

    static String listingId(String url) {
        if (url == null) return null;
        Matcher m = LISTING_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static int endOfJsStringLiteral(String s, int openQuote) {
        if (openQuote < 0) return -1;
        for (int i = openQuote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') i++;
            else if (c == '"') return i;
        }
        return -1;
    }

    static Instant parseInstant(String iso) {
        if (StringUtils.isBlank(iso)) return null;
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
