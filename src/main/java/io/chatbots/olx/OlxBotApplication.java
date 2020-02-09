package io.chatbots.olx;

import io.chatbots.olx.config.StorageConfig;
import io.chatbots.olx.grabber.OlxGrabber;
import io.chatbots.olx.grabber.OlxGrabberImpl;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.telegram.telegrambots.ApiContextInitializer;
import org.telegram.telegrambots.meta.TelegramBotsApi;

@SpringBootApplication
@EnableScheduling
@Configuration
@Import(StorageConfig.class)
public class OlxBotApplication implements InitializingBean {


    @Autowired
    private OlxTelegramBot olxTelegramBot;

    public static void main(String[] args) {
        ApiContextInitializer.init();
        SpringApplication.run(OlxBotApplication.class);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        TelegramBotsApi telegramBotsApi = new TelegramBotsApi();
        telegramBotsApi.registerBot(olxTelegramBot);
    }

    @Bean
    public OlxTelegramBot olxTelegramBot(
            @Value("${bot.name}")
            String botName,
            @Value("${bot.token}")
            String botToken
    ) {
        return new OlxTelegramBot(botName, botToken);
    }

    @Bean
    public OlxGrabber olxGrabber() {
        return new OlxGrabberImpl();
    }


    @Scheduled(fixedRate =  10 * 60 * 1_000)
    public void scheduleSending() {
        olxTelegramBot.notifySubscribedChats();
    }
}
