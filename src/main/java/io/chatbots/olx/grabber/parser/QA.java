package io.chatbots.olx.grabber.parser;

import io.chatbots.olx.grabber.Offer;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class QA extends BaseParser implements Parser {

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
            String raw = card.select("p[data-testid=location-date]").text();
            String[] parts = raw.split(" - ");
            // skip [0] (city/location), try remaining segments for a parseable date
            for (int i = 1; i < parts.length; i++) {
                LocalDateTime dt = PlUpdatedAtDateParser.parseOlxDate(parts[i]);
                if (dt != null) {
                    return LocalDateTime.ofInstant(dt.toInstant(ZoneOffset.UTC), ZoneId.of("Europe/Warsaw"));
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

            val body = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(15_000)
                    .get().body();
            String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;

            return body.getElementsByAttributeValue("data-cy", "l-card").stream()
                    .map(card -> {
                        String name = card.select("[data-cy=ad-card-title] h4").text();

                        Element linkElement = card.selectFirst("[data-cy=ad-card-title] a[href]");
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
}
