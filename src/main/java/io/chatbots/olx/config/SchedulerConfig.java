package io.chatbots.olx.config;

import io.chatbots.olx.OlxTelegramBot;
import io.chatbots.olx.checker.RegressionChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.TimeUnit;

@Configuration
@RequiredArgsConstructor
@EnableScheduling
public class SchedulerConfig {
    private final OlxTelegramBot olxTelegramBot;
    private final RegressionChecker regressionChecker;

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void scheduleSending() {
        olxTelegramBot.notifySubscribedChats();
    }

    @Scheduled(fixedDelay = 4, timeUnit = TimeUnit.HOURS)
    public void scheduleChecker() {
        regressionChecker.checkSitesForRegression();
    }

}
