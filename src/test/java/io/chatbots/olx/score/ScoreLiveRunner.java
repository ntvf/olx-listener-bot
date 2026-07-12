package io.chatbots.olx.score;

import java.lang.reflect.Field;
import java.util.function.Consumer;

/**
 * Manual end-to-end runner for the score feature (hits olx.ua and google.com — not a unit test).
 * Run: mvn test-compile exec:java -Dexec.mainClass=io.chatbots.olx.score.ScoreLiveRunner \
 *        -Dexec.classpathScope=test -Dexec.args="<listing-url>"
 */
public class ScoreLiveRunner {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0]
                : "https://www.olx.ua/d/uk/obyavlenie/iphone-14-128gb-midnight-ID10QB5v.html";

        AiModeSearchService searchService = new AiModeSearchService();
        set(searchService, "profileDir", "./.ai-chrome-profile");
        set(searchService, "answerTimeoutSeconds", 90);
        set(searchService, "captchaWaitSeconds", 240);

        CaptchaTunnelService tunnelService = new CaptchaTunnelService();
        set(tunnelService, "staticCaptchaUrl", "");
        set(tunnelService, "tunnelCommand", "cloudflared tunnel --url http://localhost:6080 --no-autoupdate");

        ScoreService scoreService = new ScoreService(new ListingScraper(), searchService, tunnelService);

        ListingInfo listing = new ListingScraper().scrape(url);
        System.out.println("=== SCRAPED ===\n" + listing);

        Consumer<String> notifier = msg -> System.out.println("=== PROGRESS ===\n" + msg);
        System.out.println("=== SUMMARY ===\n" + scoreService.scoreListing(url, notifier));
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
