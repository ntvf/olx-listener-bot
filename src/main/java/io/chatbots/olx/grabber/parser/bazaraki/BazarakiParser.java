package io.chatbots.olx.grabber.parser.bazaraki;

import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.browser.CloudflareBrowserFetcher;
import io.chatbots.olx.grabber.parser.BaseParser;
import io.chatbots.olx.grabber.parser.Parser;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a bazaraki.com listing page. Bazaraki sits behind Cloudflare's JS interstitial, so the HTML
 * is fetched through {@link CloudflareBrowserFetcher} (a real browser) rather than Jsoup, then the
 * rendered cards are extracted with Jsoup. Results are forced to newest-first, since the bot reads
 * only the first page. The top of a newest-first page still carries a few paid {@code data-t-vip}
 * ads that ignore the sort, so those are skipped as promoted.
 */
@Slf4j
public class BazarakiParser extends BaseParser implements Parser {

    private static final String READY_SELECTOR = ".advert-grid";
    private static final int READY_TIMEOUT_MS = 20_000;
    private static final String NEWEST_ORDERING = "ordering=newest";

    private final CloudflareBrowserFetcher browser;

    public BazarakiParser(CloudflareBrowserFetcher browser) {
        this.browser = browser;
    }

    @Override
    public List<Offer> parse(String url) {
        val newestFirst = withNewestOrdering(url);
        log.info("Fetching url={}", newestFirst);
        val html = browser.fetch(newestFirst, READY_SELECTOR, READY_TIMEOUT_MS);
        if (html == null) {
            return List.of();
        }
        return parseHtml(html, newestFirst);
    }

    List<Offer> parseHtml(String html, String baseUri) {
        Document doc = Jsoup.parse(html, baseUri);
        List<Offer> offers = new ArrayList<>();
        for (Element card : doc.select(".advert-grid")) {
            if (card.hasAttr("data-t-vip")) continue;
            Element title = card.selectFirst(".advert-grid__content-title");
            if (title == null || StringUtils.isBlank(title.attr("href"))) continue;
            String name = title.text();
            String price = card.getElementsByClass("advert-grid__content-price").text();
            offers.add(Offer.builder()
                    .name(name)
                    .content(StringUtils.normalizeSpace(name + " " + price))
                    .url(title.absUrl("href"))
                    .build());
        }
        return offers;
    }

    private String withNewestOrdering(String url) {
        String query = URI.create(url).getQuery();
        if (query == null || query.isBlank()) {
            return url + (url.contains("?") ? "&" : "?") + NEWEST_ORDERING;
        }
        for (String param : query.split("&")) {
            if (param.startsWith("ordering=")) {
                return url.replace(param, NEWEST_ORDERING);
            }
        }
        return url + "&" + NEWEST_ORDERING;
    }
}
