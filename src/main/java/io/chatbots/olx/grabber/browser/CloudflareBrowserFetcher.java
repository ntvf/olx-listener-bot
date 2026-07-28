package io.chatbots.olx.grabber.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Paths;
import java.time.Duration;

/**
 * Fetches pages that sit behind Cloudflare's "Just a moment…" JS interstitial, which a plain HTTP
 * client (Jsoup) can't clear because the challenge only releases the real page after running its
 * JavaScript. A headless Chromium runs that JavaScript and gets the rendered HTML.
 *
 * <p>The browser and its profile are kept alive between fetches: the Cloudflare clearance cookie
 * lives in the profile, so reusing it means most fetches skip the interstitial entirely, and
 * launching Chromium per fetch would cost seconds each time. It's recycled once idle for
 * {@code browser.fetch.session-idle-minutes}. One profile means one fetch at a time, so the method
 * is synchronized — callers here (regression canary, listing poll) are low-volume schedulers.
 *
 * <p>Unlike {@link io.chatbots.olx.score.AiModeSearchService} this runs headless and needs no
 * virtual display: Cloudflare's non-interactive challenge passes in headless Chromium, whereas
 * Google's AI Mode refuses headless browsers.
 */
@Slf4j
public class CloudflareBrowserFetcher {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    @Value("${browser.fetch.headless:true}")
    private boolean headless;
    @Value("${browser.fetch.profile-dir:./.cf-chrome-profile}")
    private String profileDir;
    @Value("${browser.fetch.session-idle-minutes:30}")
    private int sessionIdleMinutes;
    @Value("${browser.fetch.nav-timeout-ms:60000}")
    private int navTimeoutMs;

    private Playwright playwright;
    private BrowserContext context;
    private long lastUsedAt;

    /**
     * Loads {@code url} in a real browser and returns the rendered HTML, waiting up to
     * {@code readyTimeoutMs} for {@code readySelector} to appear so the content (not the Cloudflare
     * interstitial) is what gets returned.
     *
     * @return the page HTML, or {@code null} if the browser could not render it
     */
    public synchronized String fetch(String url, String readySelector, int readyTimeoutMs) {
        Page page = null;
        try {
            page = openPage();
            page.navigate(url, new Page.NavigateOptions().setTimeout(navTimeoutMs));
            boolean ready = waitForSelector(page, readySelector, readyTimeoutMs);
            if (!ready) {
                log.warn("Content '{}' never appeared on {} (title='{}'){}", readySelector, url, page.title(),
                        page.title().toLowerCase().contains("just a moment") ? " — still on Cloudflare challenge" : "");
            }
            lastUsedAt = System.currentTimeMillis();
            return page.content();
        } catch (RuntimeException e) {
            // a wedged browser would fail every future fetch; drop it and relaunch next time
            closeSession();
            log.warn("Browser fetch failed for {}", url, e);
            return null;
        } finally {
            closeQuietly(page);
        }
    }

    private boolean waitForSelector(Page page, String selector, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (page.locator(selector).count() > 0) return true;
            } catch (RuntimeException ignored) {
                // selector engine hiccup mid-navigation, retry
            }
            page.waitForTimeout(1000);
        }
        return page.locator(selector).count() > 0;
    }

    private Page openPage() {
        if (context != null && isSessionStale()) {
            log.info("Cloudflare browser idle for over {} min, recycling it", sessionIdleMinutes);
            closeSession();
        }
        if (context == null) {
            log.info("Launching Cloudflare browser (profile {}, headless={})", profileDir, headless);
            playwright = Playwright.create();
            context = playwright.chromium().launchPersistentContext(
                    Paths.get(profileDir),
                    new BrowserType.LaunchPersistentContextOptions()
                            .setHeadless(headless)
                            .setUserAgent(USER_AGENT)
                            .setViewportSize(1280, 900));
        }
        return context.newPage();
    }

    private boolean isSessionStale() {
        if (sessionIdleMinutes <= 0) return true;
        return System.currentTimeMillis() - lastUsedAt > Duration.ofMinutes(sessionIdleMinutes).toMillis();
    }

    @PreDestroy
    public synchronized void closeSession() {
        closeQuietly(context);
        closeQuietly(playwright);
        context = null;
        playwright = null;
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("Failed to close {}", closeable.getClass().getSimpleName(), e);
        }
    }
}
