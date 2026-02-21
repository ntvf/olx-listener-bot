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

    private static String getSortedByLastCreatedUrl(String url) {
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
            val updatedText = card.select("p[data-testid=location-date]").text().split(" - ")[1];
            val parsed = PlUpdatedAtDateParser.parseOlxDate(updatedText);
            return LocalDateTime.ofInstant(parsed.toInstant(ZoneOffset.UTC), ZoneId.of("Europe/Warsaw"));
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public List<Offer> parse(String url) {
        try {

            url = getSortedByLastCreatedUrl(url);

            val body = Jsoup.connect(url).get().body();
            String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;

            return body.getElementsByAttributeValue("data-cy", "l-card").stream()
                    .filter(card -> card.select("div[data-testid=adCard-featured]").isEmpty()) // not featured
                    .map(card -> {
                        // Title – the most important fix
                        String name = //card.select("h4.css-hzlye5").text();
                                // Alternative:
                                card.select("[data-cy=ad-card-title] h4").text();

                        // Link – take the one inside .css-1apmciz (second occurrence is usually cleaner)
                        Element linkElement = card.selectFirst(".css-1apmciz a[href]");
                        String link = linkElement != null ? linkElement.attr("href") : "";

                        if (link.isEmpty()) {
                            // fallback – any link
                            link = card.selectFirst("a[href]").attr("href");
                        }

                        if (link.startsWith("/")) {
                            link = baseUrl.substring(0, baseUrl.indexOf("/", 8)) + link; // https://www.olx.pl/...
                        }

                        // Right now content stays empty – list page doesn't have description
                        String content = ""; // ← can be improved only by opening detail page

                        val updatedAt = getUpdatedAt(card);
                        return Offer.builder()
                                .url(cleanUrlFromQueryParams(link))
                                .content(content)
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
