package io.chatbots.olx.config;

import io.chatbots.olx.stats.BotStatsService;
import io.chatbots.olx.stats.impl.JpaStatsService;
import io.chatbots.olx.storage.ListenerJpaRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StatsConfig {
    @Bean
    public BotStatsService botStatsService(ListenerJpaRepository repo) {
        return new JpaStatsService(repo);
    }
}
