package io.chatbots.olx.checker;

import io.chatbots.olx.grabber.OlxGrabber;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RegressionChecker {
    private final OlxGrabber olxGrabber;
    private static final List<String> URLS = Arrays.asList(
            "https://www.olx.ua/list/q-iphone/?search%5Bfilter_float_price%3Afrom%5D=100&search%5Bfilter_float_price%3Ato%5D=100000",
            "https://olx.ba/pretraga?q=iphone",
            "https://www.olx.bg/ads/q-iphone/?search%5Bdescription%5D=1",
            "https://www.olx.pl/oferty/q-iphone/",
            "https://www.olx.ro/oferte/q-iphone/",
            "https://www.olx.pt/ads/q-iphone/?search%5Bdescription%5D=1",
            "https://www.olx.uz/list/q-iphone/",
            "https://www.olx.kz/list/q-iphone/",
            "https://www.olx.com.pk/items/q-iphone");
    private final Map<String, Boolean> lastResults = new ConcurrentHashMap<>();

    public RegressionChecker(OlxGrabber grabber) {
        this.olxGrabber = grabber;
    }

    public void checkSitesForRegression() {
        URLS.forEach(url -> {
            try {
                val offers = olxGrabber.getOffers(url);
                boolean ok = !offers.isEmpty() && offers.stream().allMatch(o -> StringUtils.isNotBlank(o.getUrl()));
                lastResults.put(url, ok);
                if (!ok) {
                    log.warn("!!! URL IS NOT PARSING WELL:{}", url);
                }
            } catch (Exception e) {
                lastResults.put(url, false);
                log.warn("!!! Exception checking url:{}", url, e);
            }
        });
    }

    public Map<String, Boolean> getLastResults() {
        if (lastResults.isEmpty()) return Collections.emptyMap();
        Map<String, Boolean> ordered = new LinkedHashMap<>();
        URLS.forEach(url -> ordered.put(url, lastResults.getOrDefault(url, null)));
        return Collections.unmodifiableMap(ordered);
    }
}
