package io.chatbots.olx.config;

import io.chatbots.olx.stats.BotStatsService;
import io.chatbots.olx.stats.impl.MongoStatsService;
import org.jongo.Jongo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Import(StorageConfig.class)
@Configuration
public class StatsConfig {
    @Bean
    public BotStatsService botStatsService(Jongo jongo) {
        return new MongoStatsService(jongo);
    }
}
