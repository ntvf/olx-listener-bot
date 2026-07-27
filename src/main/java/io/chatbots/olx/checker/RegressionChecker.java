package io.chatbots.olx.checker;

import io.chatbots.olx.grabber.OlxGrabber;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
            "https://www.olx.com.pk/items/q-iphone",
            "https://www.bazaraki.com/adv/phones-and-accessories--mobile-phones-and-smartphones/?ordering=newest",
            "https://www.otodom.pl/pl/wyniki/wynajem/mieszkanie/warszawa?by=LATEST&direction=DESC",
            "https://www.otomoto.pl/osobowe?search%5Border%5D=created_at_first%3Adesc");
    private final Map<String, Boolean> lastResults = new ConcurrentHashMap<>();
    private volatile Consumer<Set<String>> regressionListener = domains -> {};

    public RegressionChecker(OlxGrabber grabber) {
        this.olxGrabber = grabber;
    }

    /** Notified with the domains that just went from parsing to not parsing. */
    public void setRegressionListener(Consumer<Set<String>> listener) {
        this.regressionListener = listener;
    }

    public void checkSitesForRegression() {
        Set<String> newlyBroken = new LinkedHashSet<>();
        URLS.forEach(url -> {
            Boolean previous = lastResults.get(url);
            boolean ok;
            try {
                val offers = olxGrabber.getOffers(url);
                ok = !offers.isEmpty() && offers.stream().allMatch(o -> StringUtils.isNotBlank(o.getUrl()));
                if (!ok) {
                    log.warn("!!! URL IS NOT PARSING WELL:{}", url);
                }
            } catch (Exception e) {
                ok = false;
                log.warn("!!! Exception checking url:{}", url, e);
            }
            lastResults.put(url, ok);
            if (Boolean.TRUE.equals(previous) && !ok) {
                newlyBroken.add(domainOf(url));
            }
        });
        if (!newlyBroken.isEmpty()) {
            regressionListener.accept(newlyBroken);
        }
    }

    public Map<String, Boolean> getLastResults() {
        if (lastResults.isEmpty()) return Collections.emptyMap();
        Map<String, Boolean> ordered = new LinkedHashMap<>();
        URLS.forEach(url -> ordered.put(domainOf(url), lastResults.getOrDefault(url, null)));
        return Collections.unmodifiableMap(ordered);
    }

    private static String domainOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? url : StringUtils.removeStart(host, "www.");
        } catch (Exception e) {
            return url;
        }
    }
}
