package io.chatbots.olx;

import io.chatbots.olx.config.StorageConfig;
import io.chatbots.olx.grabber.OlxGrabber;
import io.chatbots.olx.grabber.OlxGrabberImpl;
import io.chatbots.olx.grabber.parser.BA;
import io.chatbots.olx.grabber.parser.BR;
import io.chatbots.olx.grabber.parser.Future;
import io.chatbots.olx.grabber.parser.Parser;
import io.chatbots.olx.grabber.parser.QA;
import io.chatbots.olx.grabber.parser.Widespread;
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

import java.util.HashMap;
import java.util.Map;

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
    public OlxGrabber olxGrabber(Map<String, Parser> parsers) {
        return new OlxGrabberImpl(parsers);
    }

    @Bean
    public Map<String, Parser> parsers() {
        return new HashMap<String, Parser>() {
            {
                put("olx.ua", new Widespread());
                put("olx.ba", new BA());
                put("olx.bg", new Widespread());
                put("olx.pl", new Widespread());
                put("olx.ro", new Widespread());
                put("olx.pt", new Widespread());
                put("dubizzle.com", new Widespread());
                put("olx.com.eg", new Widespread());
                put("olx.qa", new QA());
                put("olx.com.br", new BR());
                put("olx.uz", new Widespread());
                put("olx.kz", new Widespread());
                put("olx.in", new Future());
                put("olx.co.za", new Future());
                put("olx.com.pk", new Future());
                put("olx.co.id", new Future());
                put("olx.com.ar", new Future());
                put("olx.co.cr", new Future());
            }
        };
    }


    @Scheduled(fixedRate = 10 * 60 * 1_000)
    public void scheduleSending() {
        olxTelegramBot.notifySubscribedChats();
    }
}
