package io.chatbots.olx;

import io.chatbots.olx.checker.RegressionChecker;
import io.chatbots.olx.grabber.OlxGrabber;
import io.chatbots.olx.grabber.OlxGrabberImpl;
import io.chatbots.olx.grabber.parser.BA;
import io.chatbots.olx.grabber.parser.BR;
import io.chatbots.olx.grabber.parser.Future;
import io.chatbots.olx.grabber.parser.Parser;
import io.chatbots.olx.grabber.parser.QA;
import io.chatbots.olx.grabber.parser.bazaraki.BazarakiParser;
import io.chatbots.olx.i18n.ResourceBundleTranslationService;
import io.chatbots.olx.i18n.TranslationService;
import lombok.SneakyThrows;
import lombok.val;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
@EnableCaching
public class OlxBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(OlxBotApplication.class);
    }

    @Bean
    @SneakyThrows
    public OlxTelegramBot olxTelegramBot(
            @Value("${bot.name}")
            String botName,
            @Value("${bot.token}")
            String botToken
    ) {
        val bot = new OlxTelegramBot();
        TelegramBotsLongPollingApplication telegramBotsApi = new TelegramBotsLongPollingApplication();
        telegramBotsApi.registerBot(botToken, bot);
        return bot;
    }

    @Bean
    public TelegramClient telegramClient(
            @Value("${bot.token}")
            String botToken
    ) {
        return new OkHttpTelegramClient(botToken);
    }

    @Bean
    public OlxGrabber olxGrabber(Map<String, Parser> parsers) {
        return new OlxGrabberImpl(parsers);
    }

    @Bean
    public Map<String, Parser> parsers() {
        return new HashMap<String, Parser>() {
            {
                put("olx.ua", new QA());
                put("olx.ba", new BA());
                put("olx.bg", new QA());
                put("olx.pl", new QA());
                put("olx.ro", new QA());
                put("olx.pt", new QA());
                put("dubizzle.com", new QA());
                put("olx.com.eg", new QA());
                put("olx.qa", new QA());
                put("olx.com.br", new BR());
                put("olx.uz", new QA());
                put("olx.kz", new QA());
                put("olx.in", new Future());
                put("olx.co.za", new Future());
                put("olx.com.pk", new Future());
                put("olx.co.id", new Future());
                put("olx.com.ar", new Future());
                put("olx.co.cr", new Future());
                put("bazaraki.com", new BazarakiParser());
            }
        };
    }


    @Bean
    public TranslationService translationService() {
        return new ResourceBundleTranslationService();
    }

    @Bean
    public RegressionChecker checker(OlxGrabber grabber) {
        return new RegressionChecker(grabber);
    }

}
