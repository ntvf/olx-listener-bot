package io.chatbots.olx.grabber.parser;

import io.chatbots.olx.grabber.Offer;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class OlxPkParser extends BaseParser implements Parser {

    @Override
    public List<Offer> parse(String url) {
        try {
            if (!url.contains("sort=")) {
                url += (url.contains("?") ? "&" : "?") + "sort=date";
            }
            log.info("Fetching url={}", url);

            val body = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(15_000)
                    .get().body();

            return body.select("article").stream()
                    .filter(a -> a.selectFirst("[aria-label=Featured]") == null)
                    .filter(a -> a.selectFirst("[aria-label=Delivery]") == null)
                    .map(article -> {
                        Element link = article.selectFirst("a[href]");
                        if (link == null) return null;
                        String href = link.attr("href");
                        if (href.startsWith("/")) href = "https://www.olx.com.pk" + href;
                        Element h2 = article.selectFirst("h2");
                        String name = h2 != null ? h2.text() : link.attr("title");
                        return Offer.builder()
                                .url(cleanUrlFromQueryParams(href))
                                .name(name)
                                .content("")
                                .build();
                    })
                    .filter(o -> o != null && StringUtils.isNotBlank(o.getName()) && StringUtils.isNotBlank(o.getUrl()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Error while parsing olx.com.pk url: " + url, e);
            return Collections.emptyList();
        }
    }
}
