package io.chatbots.olx.config;

import io.chatbots.olx.OlxTelegramBot;
import io.chatbots.olx.checker.RegressionChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@RequiredArgsConstructor
@EnableScheduling
public class SchedulerConfig {
    private final OlxTelegramBot olxTelegramBot;
    private final RegressionChecker regressionChecker;

    //            @Scheduled(fixedRate = 1* 20 * 1_000)
    @Scheduled(fixedRate = 10 * 60 * 1_000)
    public void scheduleSending() {
        olxTelegramBot.notifySubscribedChats();
    }

    @Scheduled(fixedRate = 4 * 60 * 60 * 1_000)
    public void scheduleChecker() {
        regressionChecker.checkSitesForRegression();
    }

}
