package io.chatbots.olx.furniture;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.chatbots.olx.score.AiModeSearchService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Asks Google AI Mode for a used-IKEA item's dimensions <b>from its photo</b> — the signal the
 * seller left out of the text (Kallax grid, drawer/seat count). Drives the AI-Mode composer
 * (attach via the "+" menu → type → send → read the MODEL/DIMS/SOURCE answer) through a headed
 * Chromium, mirroring {@link AiModeSearchService} but for image input. Its own persistent profile
 * keeps it clear of the score feature's browser. Best-effort by contract: every failure throws and
 * the caller leaves the offer on the model median, so a wedged CAPTCHA never blocks posting.
 */
@Slf4j
public class FurniturePhotoDimensionService {

    private static final String PROMPT =
            "Photo of a used IKEA item. Reply 3 lines only: MODEL: <model+variant>  "
          + "DIMS: WxDxH cm  SOURCE: photo+catalog|unknown";

    @Value("${furniture.photo-ai.profile-dir:./.ai-chrome-furniture-profile}")
    private String profileDir;
    @Value("${furniture.photo-ai.answer-timeout-seconds:45}")
    private int answerTimeoutSeconds;
    @Value("${furniture.photo-ai.captcha-wait-seconds:240}")
    private int captchaWaitSeconds;
    @Value("${furniture.photo-ai.session-idle-minutes:20}")
    private int sessionIdleMinutes;

    private Playwright playwright;
    private BrowserContext context;
    private long lastUsedAt;

    /**
     * @return the raw AI-Mode answer text (parsed by {@link FurnitureAiAnswerParser}); never null.
     * @throws RuntimeException on any browser/UI/CAPTCHA failure — the caller treats this as "no size".
     */
    public synchronized String describe(Path image, Consumer<AiModeSearchService.CaptchaEvent> captchaNotifier) {
        Page page = null;
        try {
            page = openPage();
            page.navigate("https://www.google.com/search?udm=50&hl=en&q=",
                    new Page.NavigateOptions().setTimeout(60_000));
            acceptConsentIfShown(page);
            waitOutCaptcha(page, captchaNotifier);
            page.waitForTimeout(800);

            if (!attach(page, image)) throw new IllegalStateException("could not attach the photo to AI Mode");
            page.waitForTimeout(1200);
            if (!typePrompt(page)) throw new IllegalStateException("could not find the AI Mode composer");
            if (!clickSend(page)) page.keyboard().press("Enter");

            String answer = waitForAnswer(page);
            lastUsedAt = System.currentTimeMillis();
            return answer;
        } catch (RuntimeException e) {
            closeSession(); // a wedged browser would fail every future call; drop and relaunch next time
            throw e;
        } finally {
            closeQuietly(page);
        }
    }

    private Page openPage() {
        if (context != null && isSessionStale()) {
            log.info("Furniture photo AI browser idle over {} min, recycling", sessionIdleMinutes);
            closeSession();
        }
        if (context == null) {
            log.info("Launching furniture photo AI browser (profile {})", profileDir);
            playwright = Playwright.create();
            context = playwright.chromium().launchPersistentContext(Paths.get(profileDir),
                    new BrowserType.LaunchPersistentContextOptions().setHeadless(false).setViewportSize(1400, 950));
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

    private void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception e) {
            log.debug("close {} failed", c.getClass().getSimpleName(), e);
        }
    }

    private void acceptConsentIfShown(Page page) {
        if (!page.url().contains("consent.google.com")) return;
        Locator btn = page.locator("button:has-text(\"Accept all\"), button:has-text(\"I agree\"),"
                + " form[action*=consent] button").first();
        if (btn.count() > 0) {
            btn.click();
            page.waitForTimeout(2000);
        }
    }

    private void waitOutCaptcha(Page page, Consumer<AiModeSearchService.CaptchaEvent> captchaNotifier) {
        if (!page.url().contains("/sorry/")) return;
        log.warn("AI Mode CAPTCHA shown; notifying the linking chat, waiting up to {}s", captchaWaitSeconds);
        captchaNotifier.accept(AiModeSearchService.CaptchaEvent.builder().waitSeconds(captchaWaitSeconds).build());
        long deadline = System.currentTimeMillis() + captchaWaitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline && page.url().contains("/sorry/")) {
            page.waitForTimeout(1500);
        }
        if (page.url().contains("/sorry/")) throw new IllegalStateException("CAPTCHA not solved in time");
        page.waitForLoadState();
    }

    /** Attach via a hidden file input if present, else open the "+" menu and pick "Add images". */
    private boolean attach(Page page, Path image) {
        try {
            Locator input = page.locator("input[type=file]");
            if (input.count() > 0) {
                input.first().setInputFiles(image);
                return true;
            }
        } catch (Exception e) {
            log.debug("hidden file input attach failed: {}", e.getMessage());
        }
        try {
            Locator plus = page.locator("button[aria-label*='Add' i]").first();
            if (plus.count() == 0) return false;
            FileChooser chooser = page.waitForFileChooser(
                    new Page.WaitForFileChooserOptions().setTimeout(8000), () -> {
                        plus.click(new Locator.ClickOptions().setForce(true));
                        page.getByText("Add images").first().click();
                    });
            chooser.setFiles(image);
            return true;
        } catch (Exception e) {
            log.debug("plus-menu attach failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean typePrompt(Page page) {
        for (String sel : new String[]{"textarea[aria-label*='Ask' i]", "[aria-label='Ask anything']",
                "textarea", "[role='textbox']", "div[contenteditable='true']", "[role='combobox']"}) {
            try {
                Locator box = page.locator(sel).first();
                if (box.count() == 0) continue;
                box.click();
                try {
                    box.fill(PROMPT);
                } catch (Exception ignore) {
                    page.keyboard().type(PROMPT);
                }
                return true;
            } catch (Exception e) {
                log.debug("composer[{}] failed: {}", sel, e.getMessage());
            }
        }
        return false;
    }

    private boolean clickSend(Page page) {
        for (String name : new String[]{"Send", "Submit", "Search"}) {
            try {
                Locator b = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(name)).last();
                if (b.count() > 0 && b.isEnabled()) {
                    b.click();
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** Poll the page text for the MODEL/DIMS/SOURCE answer block (concrete values, not the prompt echo). */
    private String waitForAnswer(Page page) {
        long deadline = System.currentTimeMillis() + answerTimeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                String body = page.locator("body").innerText();
                int m = body.lastIndexOf("MODEL:");
                if (m >= 0) {
                    String[] lines = body.substring(m).split("\n");
                    String model = lines.length > 0 ? lines[0].trim() : "";
                    if (!model.contains("<") && model.length() > "MODEL:".length() + 1) {
                        StringBuilder out = new StringBuilder();
                        for (String line : lines) {
                            String t = line.trim();
                            if (t.startsWith("MODEL:") || t.startsWith("DIMS:") || t.startsWith("SOURCE:")) {
                                out.append(t).append('\n');
                            }
                            if (t.startsWith("SOURCE:")) return out.toString().trim();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            page.waitForTimeout(700);
        }
        return "";
    }
}
