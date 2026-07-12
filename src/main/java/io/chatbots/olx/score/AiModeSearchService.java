package io.chatbots.olx.score;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * Queries Google Search AI Mode (udm=50) through a real (headed) Chromium driven by Playwright.
 * <p>
 * Headless does not work: Google replies "AI Mode is not currently available on your device or
 * account" to headless browsers, so on a server this needs a virtual display (Xvfb). See
 * docs/ai-score-setup.md. A persistent profile keeps consent/CAPTCHA cookies between runs.
 * <p>
 * When Google asks for a CAPTCHA the {@code captchaNotifier} is invoked (the bot posts the noVNC
 * link to the chat) and the service waits for a human to solve it in the bot's browser session.
 */
@Slf4j
public class AiModeSearchService {

    // Tightest first; Google rotates obfuscated markup, so we fall back to the whole main region.
    private static final String[] ANSWER_SELECTORS = {
            "div[data-subtree=\"aimc\"]",
            "div[jsname][data-hveid] div[aria-live]",
            "div[role=\"main\"]",
    };

    @Value("${ai.score.profile-dir:./.ai-chrome-profile}")
    private String profileDir;
    @Value("${ai.score.answer-timeout-seconds:90}")
    private int answerTimeoutSeconds;
    @Value("${ai.score.captcha-wait-seconds:240}")
    private int captchaWaitSeconds;

    /**
     * Synchronized: one browser profile means one query at a time.
     *
     * @param captchaNotifier invoked once if Google demands a CAPTCHA, before waiting for a human
     * @return the AI Mode answer text
     */
    public synchronized String search(String query, Consumer<CaptchaEvent> captchaNotifier) {
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.chromium().launchPersistentContext(
                    Paths.get(profileDir),
                    new BrowserType.LaunchPersistentContextOptions()
                            .setHeadless(false)
                            .setViewportSize(1280, 900));
            try {
                Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                String url = "https://www.google.com/search?udm=50&hl=en&q="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8);
                page.navigate(url, new Page.NavigateOptions().setTimeout(60_000));

                acceptConsentIfShown(page);
                waitOutCaptcha(page, captchaNotifier);

                String answer = waitForStableAnswer(page);
                if (answer == null || answer.isBlank()) {
                    String body = safeInnerText(page.locator("body").first());
                    if (body.contains("AI Mode is not currently available")) {
                        throw new IllegalStateException(
                                "Google refused AI Mode for this browser (\"not available on your device or account\"). "
                                        + "Make sure the bot runs a headed browser on a display (Xvfb), not headless.");
                    }
                    throw new IllegalStateException("Could not extract an AI Mode answer (layout change or empty response).");
                }
                return stripBoilerplate(answer);
            } finally {
                context.close();
            }
        }
    }

    @Data
    @Builder
    public static class CaptchaEvent {
        private int waitSeconds;
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

    /** The answer streams in; poll until the text stops growing. */
    private String waitForStableAnswer(Page page) {
        long deadline = System.currentTimeMillis() + answerTimeoutSeconds * 1000L;
        String last = "";
        int stableTicks = 0;
        while (System.currentTimeMillis() < deadline) {
            page.waitForTimeout(800);
            String text = safeInnerText(findAnswerLocator(page));
            if (text.length() > 200 && text.equals(last)) {
                if (++stableTicks >= 3) return text;
            } else {
                stableTicks = 0;
            }
            last = text;
        }
        return last; // best effort on timeout
    }

    private Locator findAnswerLocator(Page page) {
        for (String selector : ANSWER_SELECTORS) {
            Locator locator = page.locator(selector).first();
            try {
                if (locator.count() > 0) return locator;
            } catch (Exception ignored) {
                // selector engine hiccup, try the next one
            }
        }
        return page.locator("body").first();
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
