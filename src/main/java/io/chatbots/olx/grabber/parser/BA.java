package io.chatbots.olx.grabber.parser;

import io.chatbots.olx.grabber.Offer;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class BA extends BaseParser implements Parser {

    // Matches listing entries in the __NUXT__ SSR state embedded in the page HTML
    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "\\{score:[^,]+,id:(\\d+),type:\\w,title:\"([^\"]+)\""
    );

    private static String unescapeJs(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            if (i + 5 < s.length() && s.charAt(i) == '\\' && s.charAt(i + 1) == 'u') {
                try {
                    int cp = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                    sb.append((char) cp);
                    i += 6;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append(s.charAt(i++));
        }
        return sb.toString();
    }

    static String toSlug(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        t = t.replaceAll("[^a-z0-9 ]", "");
        t = t.trim().replaceAll(" +", "-");
        return t;
    }

    @Override
    public List<Offer> parse(String url) {
        try {
            log.info("Fetching url={}", url);
            String html = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept-Language", "bs-BA,bs;q=0.9,en;q=0.8")
                    .timeout(15_000)
                    .execute()
                    .body();

            List<Offer> offers = new ArrayList<>();
            Matcher m = RESULT_PATTERN.matcher(html);
            while (m.find()) {
                long id = Long.parseLong(m.group(1));
                String title = unescapeJs(m.group(2));
                String slug = toSlug(title);
                String listingUrl = "https://olx.ba/oglas/" + slug + "/" + id;

                offers.add(Offer.builder()
                        .url(listingUrl)
                        .name(title)
                        .content("")
                        .build());
            }
            return offers;
        } catch (Exception e) {
            log.warn("Error while parsing BA url: " + url, e);
            return Collections.emptyList();
        }
    }
}
