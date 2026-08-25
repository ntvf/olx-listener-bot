package io.chatbots.olx.checker;

import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.OlxGrabber;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
            "https://www.bazaraki.com/telephones/mobile-phones/?ordering=newest",
            "https://www.otodom.pl/pl/wyniki/wynajem/mieszkanie/mazowieckie/warszawa/warszawa/warszawa?by=LATEST&direction=DESC",
            "https://www.otomoto.pl/osobowe?search%5Border%5D=created_at_first%3Adesc");
    private final Map<String, Boolean> lastResults = new ConcurrentHashMap<>();
    private volatile Consumer<Set<String>> regressionListener = domains -> {};
    private final Duration perUrlTimeout;
    /**
     * Probes run off the scheduler thread so a hung fetch cannot stall it. Threads are daemons and
     * the pool is unbounded on purpose: a probe past its deadline is abandoned, not waited on, and
     * an abandoned thread blocked in a socket read would otherwise occupy a fixed pool forever.
     */
    private final ExecutorService probeExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "regression-probe");
        thread.setDaemon(true);
        return thread;
    });

    public RegressionChecker(OlxGrabber grabber, Duration perUrlTimeout) {
        this.olxGrabber = grabber;
        this.perUrlTimeout = perUrlTimeout;
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
                val offers = probe(url);
                ok = !offers.isEmpty() && offers.stream().allMatch(o -> StringUtils.isNotBlank(o.getUrl()));
                if (!ok) {
                    log.warn("!!! URL IS NOT PARSING WELL:{}", url);
                }
            } catch (TimeoutException e) {
                ok = false;
                log.warn("!!! URL TIMED OUT after {}:{}", perUrlTimeout, url);
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

    /**
     * A site that stops responding mid-body can outlast the parsers' own read timeouts — otomoto
     * once held the scheduler for hours despite a 15s Jsoup timeout, which stopped every feed poll
     * and channel post until the process was restarted. The deadline here is the backstop that makes
     * a single unresponsive site cost one abandoned probe rather than the whole pipeline.
     */
    private List<Offer> probe(String url) throws Exception {
        Future<List<Offer>> probe = probeExecutor.submit(() -> olxGrabber.getOffers(url));
        try {
            return probe.get(perUrlTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            probe.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : new IllegalStateException(e.getCause());
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
