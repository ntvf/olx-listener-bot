package io.chatbots.olx;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.chatbots.olx.checker.RegressionChecker;
import io.chatbots.olx.grabber.OlxGrabber;
import io.chatbots.olx.grabber.OlxGrabberImpl;
import io.chatbots.olx.grabber.parser.BA;
import io.chatbots.olx.grabber.parser.OlxPkParser;
import io.chatbots.olx.grabber.parser.Parser;
import io.chatbots.olx.grabber.parser.QA;
import io.chatbots.olx.grabber.parser.bazaraki.BazarakiParser;
import io.chatbots.olx.i18n.ResourceBundleTranslationService;
import io.chatbots.olx.i18n.TranslationService;
import io.chatbots.olx.score.AiModeSearchService;
import io.chatbots.olx.score.CaptchaTunnelService;
import io.chatbots.olx.score.ListingScraper;
import io.chatbots.olx.score.ScoreService;
import lombok.SneakyThrows;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@EnableCaching(proxyTargetClass = false)
@RegisterReflectionForBinding({
        ApiResponse.class, Update.class
})
public class OlxBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(OlxBotApplication.class, args);
    }

    @Bean
    public OlxTelegramBot olxTelegramBot() {
        return new OlxTelegramBot();
    }

    @Bean
    @SneakyThrows
    public ApplicationListener<ApplicationReadyEvent> telegramBotRegistrar(
            OlxTelegramBot bot,
            @Value("${bot.token}") String botToken
    ) {
        return event -> {
            try {
                TelegramBotsLongPollingApplication telegramBotsApi = new TelegramBotsLongPollingApplication(
                        ObjectMapper::new,
                        () -> {
                            Dispatcher dispatcher = new Dispatcher();
                            dispatcher.setMaxRequests(100);
                            dispatcher.setMaxRequestsPerHost(100);
                            return new OkHttpClient.Builder()
                                    .dispatcher(dispatcher)
                                    .connectionPool(new ConnectionPool(100, 75, TimeUnit.SECONDS))
                                    .readTimeout(100, TimeUnit.SECONDS)
                                    .writeTimeout(70, TimeUnit.SECONDS)
                                    .connectTimeout(75, TimeUnit.SECONDS)
                                    .callTimeout(75, TimeUnit.SECONDS)
                                    .build();
                        }
                );
                telegramBotsApi.registerBot(
                        botToken,
                        () -> TelegramUrl.DEFAULT_URL,
                        offset -> GetUpdates.builder()
                                .limit(100)
                                .timeout(50)
                                .offset(offset + 1)
                                .allowedUpdates(List.of("message", "callback_query", "my_chat_member"))
                                .build(),
                        bot
                );
            } catch (Exception e) {
                throw new RuntimeException("Failed to register Telegram bot", e);
            }
        };
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

                put("olx.uz", new QA());
                put("olx.kz", new QA());
                put("olx.com.pk", new OlxPkParser());
                put("bazaraki.com", new BazarakiParser());
            }
        };
    }


    @Bean
    public ListingScraper listingScraper() {
        return new ListingScraper();
    }

    @Bean
    public AiModeSearchService aiModeSearchService() {
        return new AiModeSearchService();
    }

    @Bean
    public CaptchaTunnelService captchaTunnelService() {
        return new CaptchaTunnelService();
    }

    @Bean
    public ScoreService scoreService(ListingScraper listingScraper,
                                     AiModeSearchService aiModeSearchService,
                                     CaptchaTunnelService captchaTunnelService) {
        return new ScoreService(listingScraper, aiModeSearchService, captchaTunnelService);
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
