package io.chatbots.olx.score;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Queries Google Search AI Mode (udm=50) through a real (headed) Chromium driven by Playwright.
 * <p>
 * Headless does not work: Google replies "AI Mode is not currently available on your device or
 * account" to headless browsers, so on a server this needs a virtual display (Xvfb). See
 * docs/ai-score-setup.md. A persistent profile keeps consent/CAPTCHA cookies between runs.
 * <p>
 * The browser is kept alive between listings and only recycled once it has been idle for
 * {@code ai.score.session-idle-minutes}: launching Chromium costs seconds per listing, and a
 * fingerprint that churns on every query draws more CAPTCHAs than a stable one. Each listing still
 * gets its own page, i.e. its own AI Mode conversation — AI Mode remembers previous turns, so
 * reusing one conversation would let the previous listing bleed into the next one's answer.
 * <p>
 * When Google asks for a CAPTCHA the {@code captchaNotifier} is invoked (the bot posts the noVNC
 * link to the chat) and the service waits for a human to solve it in the bot's browser session.
 */
@Slf4j
public class AiModeSearchService {

    /**
     * The AI Mode answer container. Unlike the old fallback chain this must not degrade to
     * {@code div[role="main"]} or {@code body}: those match on an ordinary results page too, so a
     * failed AI Mode render would silently return plain SERP text as if it were the AI answer.
     */
    private static final String[] ANSWER_SELECTORS = {
            "div[data-subtree=\"aimc\"]",
            "div[jsname][data-hveid] div[aria-live]",
    };

    @Value("${ai.score.profile-dir:./.ai-chrome-profile}")
    private String profileDir;
    @Value("${ai.score.answer-timeout-seconds:90}")
    private int answerTimeoutSeconds;
    @Value("${ai.score.captcha-wait-seconds:240}")
    private int captchaWaitSeconds;
    @Value("${ai.score.session-idle-minutes:20}")
    private int sessionIdleMinutes;

    private Playwright playwright;
    private BrowserContext context;
    private long lastUsedAt;

    /**
     * Synchronized: one browser profile means one query at a time.
     *
     * @param captchaNotifier invoked once if Google demands a CAPTCHA, before waiting for a human
     * @return the AI Mode answer text
     */
    public synchronized String search(String query, Consumer<CaptchaEvent> captchaNotifier) {
        Page page = null;
        try {
            page = openPage();
            String url = "https://www.google.com/search?udm=50&hl=en&q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);
            page.navigate(url, new Page.NavigateOptions().setTimeout(60_000));

            acceptConsentIfShown(page);
            waitOutCaptcha(page, captchaNotifier);

            Locator answerLocator = waitForAnswerContainer(page);
            String answer = waitForStableAnswer(answerLocator);
            if (answer.isBlank()) {
                throw new IllegalStateException("AI Mode answered with empty text.");
            }
            lastUsedAt = System.currentTimeMillis();
            return stripBoilerplate(answer);
        } catch (RuntimeException e) {
            // a wedged browser would fail every future score; drop it and relaunch next time
            closeSession();
            throw e;
        } finally {
            closeQuietly(page);
        }
    }

    @Data
    @Builder
    public static class CaptchaEvent {
        private int waitSeconds;
    }

    /** Reuses the running browser unless it went stale, and gives the listing a fresh page. */
    private Page openPage() {
        if (context != null && isSessionStale()) {
            log.info("AI Mode browser idle for over {} min, recycling it", sessionIdleMinutes);
            closeSession();
        }
        if (context == null) {
            log.info("Launching AI Mode browser (profile {})", profileDir);
            playwright = Playwright.create();
            context = playwright.chromium().launchPersistentContext(
                    Paths.get(profileDir),
                    new BrowserType.LaunchPersistentContextOptions()
                            .setHeadless(false)
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

    private void acceptConsentIfShown(Page page) {
        if (!page.url().contains("consent.google.com")) return;
        log.info("Google consent screen shown, accepting");
        Locator btn = page.locator(
                "button:has-text(\"Accept all\"), button:has-text(\"I agree\"), form[action*=consent] button").first();
        if (btn.count() > 0) {
            btn.click();
            waitForUrl(page, u -> !u.contains("consent.google.com"), 30);
        }
    }

    private void waitOutCaptcha(Page page, Consumer<CaptchaEvent> captchaNotifier) {
        if (!page.url().contains("/sorry/")) return;
        log.warn("Google CAPTCHA page shown, notifying chat and waiting up to {}s", captchaWaitSeconds);
        captchaNotifier.accept(CaptchaEvent.builder().waitSeconds(captchaWaitSeconds).build());
        if (!waitForUrl(page, u -> !u.contains("/sorry/"), captchaWaitSeconds)) {
            throw new IllegalStateException("CAPTCHA was not solved in time, giving up.");
        }
        page.waitForLoadState();
    }

    private boolean waitForUrl(Page page, java.util.function.Predicate<String> ok, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (ok.test(page.url())) return true;
            page.waitForTimeout(1000);
        }
        return ok.test(page.url());
    }

    /**
     * Waits until an actual AI Mode container exists, rather than reading whatever is on the page.
     * If Google served an ordinary results page instead, this fails loudly: a plain SERP scrape
     * reads like a plausible answer and would otherwise reach the chat unnoticed.
     */
    private Locator waitForAnswerContainer(Page page) {
        long deadline = System.currentTimeMillis() + answerTimeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (String selector : ANSWER_SELECTORS) {
                Locator locator = page.locator(selector).first();
                try {
                    if (locator.count() > 0) return locator;
                } catch (Exception ignored) {
                    // selector engine hiccup, try the next one
                }
            }
            page.waitForTimeout(500);
        }
        String body = safeInnerText(page.locator("body").first());
        if (body.contains("AI Mode is not currently available")) {
            throw new IllegalStateException(
                    "Google refused AI Mode for this browser (\"not available on your device or account\"). "
                            + "Make sure the bot runs a headed browser on a display (Xvfb), not headless.");
        }
        throw new IllegalStateException(
                "Google served a page without an AI Mode answer (layout change, or AI Mode was not honoured).");
    }

    /** The answer streams in; poll until the text stops growing. */
    private String waitForStableAnswer(Locator answerLocator) {
        long deadline = System.currentTimeMillis() + answerTimeoutSeconds * 1000L;
        String last = "";
        int stableTicks = 0;
        while (System.currentTimeMillis() < deadline) {
            answerLocator.page().waitForTimeout(800);
            String text = safeInnerText(answerLocator);
            if (!text.isBlank() && text.equals(last)) {
                if (++stableTicks >= 3) return text;
            } else {
                stableTicks = 0;
            }
            last = text;
        }
        return last; // best effort on timeout
    }

    private String safeInnerText(Locator locator) {
        try {
            return locator.innerText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String stripBoilerplate(String answer) {
        // AI Mode ends with a follow-up question ("Would you like ...") followed by citation
        // cards; cut there — everything after is noise for a chat summary.
        int followUp = answer.indexOf("\nWould you like");
        if (followUp > 200) {
            answer = answer.substring(0, followUp);
        }
        return answer
                .replaceAll("(?m)^AI responses may include mistakes\\.?( Learn more)?$", "")
                .strip();
    }
}
