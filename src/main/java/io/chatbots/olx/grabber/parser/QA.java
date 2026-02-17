package io.chatbots.olx.grabber.parser;

import io.chatbots.olx.grabber.Offer;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class QA extends BaseParser implements Parser {
    @Override
    public List<Offer> parse(String url) {
        try {

            if (url.contains("?")) {
                url = url + "&";
            } else {
                url = url + "?";
            }
            url = url + "search%5Border%5D=created_at%3Adesc";

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

                        return Offer.builder()
                                .url(cleanUrlFromQueryParams(link))
                                .content(content)
                                .name(name)
                                .build();
                    })
                    .filter(offer -> !offer.getName().isBlank() && !offer.getUrl().isBlank()) // skip broken cards
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Error while parsing url:" + url, e);
            return Collections.emptyList();
        }
    }
}
