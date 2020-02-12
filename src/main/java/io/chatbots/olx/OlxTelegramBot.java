package io.chatbots.olx;

import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.OlxGrabber;
import io.chatbots.olx.stats.BotStats;
import io.chatbots.olx.stats.BotStatsService;
import io.chatbots.olx.stats.UserLocaleStats;
import io.chatbots.olx.storage.ListenerStorage;
import io.chatbots.olx.storage.entity.Listener;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import javax.annotation.PreDestroy;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class OlxTelegramBot extends TelegramLongPollingBot {

    private static final String REMOVE_PREFIX = "/r_";
    private static final String LISTENERS_COMMAND = "/listeners";
    private static final String ACTIVE_LISTENERS_LINK = LISTENERS_COMMAND + " - активные подписки\r\n\r\n";

    @Autowired
    private ListenerStorage listenerStorage;

    @Autowired
    private BotStatsService botStatsService;

    @Autowired
    private OlxGrabber olxGrabber;

    private ExecutorService executors = Executors.newWorkStealingPool(10);

    private String botName;
    private String botToken;

    public OlxTelegramBot(String botName, String botToken) {
        this.botName = botName;
        this.botToken = botToken;
    }

    @PreDestroy
    public void destroy() {
        executors.shutdown();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            List<BooleanSupplier> handlers = registerHandlers(update);
            walkThrough(handlers);
        } catch (Exception e) {
            log.warn("Exception while answering to request update:{}", update, e);
            sendStacktraceToChat(update, e);
        }
    }

    private List<BooleanSupplier> registerHandlers(Update update) {
        List<BooleanSupplier> handlers = new ArrayList<>();
        handlers.add(execute(this::start, update, true));
        handlers.add(execute(this::listListeners, update, true));
        handlers.add(execute(this::removeListener, update, true));
        handlers.add(execute(this::addListener, update, true));
        handlers.add(execute(this::stat, update, true));
        handlers.add(execute(this::unknownCommand, update, true));

        return handlers;
    }

    private HandleResult unknownCommand(Update update) {
        return HandleResult.EMPTY;
    }

    private HandleResult stat(Update update) {
        if ("/stats".equals(update.getMessage().getText())) {
            val botStats = botStatsService.getBotStats();

            return HandleResult.builder().botApiMethod(
                    new SendMessage()
                            .enableMarkdown(true)
                            .setChatId(update.getMessage().getChatId())
                            .setText("all listeners: " + botStats.getAllListenersCount() + "\r\n" +
                                    "active listeners: " + botStats.getActiveListenersCount() + "\r\n" +
                                    "all users: " + botStats.getAllUsersCount() + "\r\n" +
                                    "active users: " + botStats.getActiveUsersCount() + "\r\n" +
                                    "users by language" + "\r\n" +
                                    "all: " + formatLocales(botStats.getAllUsersLocales()) + "\r\n" +
                                    "active: " + formatLocales(botStats.getActiveUsersLocales()))
            ).build();
        }
        return HandleResult.EMPTY;
    }

    private String formatLocales(UserLocaleStats localeStats) {
        return localeStats.getLocalesCount().entrySet()
                .stream().map(it -> it.getKey() + ":" + it.getValue())
                .collect(Collectors.joining(","));
    }

    private HandleResult addListener(Update update) {
        String url = update.getMessage().getText();
        if (StringUtils.containsIgnoreCase(url, "http")) {
            if (!new UrlValidator().isValid(url)) {
                return HandleResult.builder().botApiMethod(
                        new SendMessage()
                                .enableMarkdown(true)
                                .setChatId(update.getMessage().getChatId())
                                .setText("❌ Не валидный url")

                ).build();
            }
            User user = update.getMessage().getFrom();
            val newListener = Listener.builder()
                    .userId(user.getId())
                    .chatId(update.getMessage().getChatId())
                    .userFirstName(user.getFirstName())
                    .userLastName(user.getLastName())
                    .userName(user.getUserName())
                    .userLanguageCode(user.getLanguageCode())
                    .updated(new Date())
                    .url(url)
                    .active(true)
                    .build();
            listenerStorage.saveListener(newListener);
            return HandleResult.builder().botApiMethod(
                    new SendMessage()
                            .enableMarkdown(true)
                            .setChatId(update.getMessage().getChatId())
                            .setText("✅ Ваш запрос сохранен")

            ).build();
        }
        return HandleResult.EMPTY;
    }

    private HandleResult removeListener(Update update) {
        if (update.getMessage().getText().startsWith(REMOVE_PREFIX)) {
            String id = update.getMessage().getText().split(REMOVE_PREFIX)[1];
            listenerStorage.deleteListener(id, update.getMessage().getChatId());
            return HandleResult.builder().botApiMethod(
                    new SendMessage()
                            .enableMarkdown(true)
                            .setChatId(update.getMessage().getChatId())
                            .setText("✅ Ваш запрос удален")

            ).build();
        }
        return HandleResult.EMPTY;
    }

    private HandleResult listListeners(Update update) {
        if (!LISTENERS_COMMAND.equals(update.getMessage().getText())) {
            return HandleResult.EMPTY;
        }
        HandleResult.HandleResultBuilder resultBuilder = HandleResult.builder();


        List<Listener> listeners = listenerStorage.getChatListeners(update.getMessage().getChatId());
        if (listeners.isEmpty()) {
            resultBuilder.botApiMethod(
                    new SendMessage()
                            .enableMarkdown(true)
                            .setChatId(update.getMessage().getChatId())
                            .setText("У вас нет запросов на наблюдение:\r\n\r\n" +
                                    "/start")
            );
        } else {
            listeners
                    .forEach(listener -> resultBuilder.botApiMethod(
                            new SendMessage()
                                    .enableMarkdown(false)
                                    .setChatId(update.getMessage().getChatId())
                                    .setText("URL:" +
                                            listener.getUrl() + "\r\n" +
                                            "Удалить: " + REMOVE_PREFIX + listener.getId())
                            )

                    );
        }
        return resultBuilder.build();
    }

    private void walkThrough(List<BooleanSupplier> handlers) {
        for (BooleanSupplier supplier : handlers) {
            if (supplier.getAsBoolean()) return;
        }
    }

    private void sendStacktraceToChat(Update update, Exception exception) {
        try {
            StringWriter stringWriter = new StringWriter();
            exception.printStackTrace(new PrintWriter(stringWriter));
            log.error("There was an error, update:{}", update, exception);
        } catch (Exception e) {
            log.warn("Exception while sending stacktrace to chat:", e);
        }
    }

    private BooleanSupplier execute(Function<Update, HandleResult> handler, Update update, boolean interrupt) {
        return () -> processResults(handler.apply(update)) && interrupt;
    }

    @SneakyThrows(TelegramApiException.class)
    private synchronized boolean processResults(HandleResult handleResult) {
        for (BotApiMethod method : handleResult.getBotApiMethods()) {
            execute(method);
        }
        for (SendDocument document : handleResult.getSendDocuments()) {
            execute(document);
        }
        handleResult.getCallBack().run();
        if (handleResult != HandleResult.EMPTY) {
            return true;
        } else {
            return false;
        }
    }

    private HandleResult start(Update update) {
        if ("/start".equals(update.getMessage().getText())) {
            ReplyKeyboardMarkup replyKeyboardMarkup = new ReplyKeyboardMarkup();
            replyKeyboardMarkup.setSelective(true);
            replyKeyboardMarkup.setResizeKeyboard(true);
            replyKeyboardMarkup.setOneTimeKeyboard(false);

            List<KeyboardRow> keyboard = new ArrayList<>();
            KeyboardRow keyboardFirstRow = new KeyboardRow();
            keyboardFirstRow.add(LISTENERS_COMMAND);
            keyboard.add(keyboardFirstRow);
            replyKeyboardMarkup.setKeyboard(keyboard);
            return HandleResult.builder().botApiMethod(
                    new SendMessage()
                            .enableMarkdown(true)
                            .setChatId(update.getMessage().getChatId())
                            .setReplyMarkup(replyKeyboardMarkup)
                            .setText("⭐⭐⭐⭐⭐\r\n" +
                                    "Привет! Я мониторю olx.ua на новые обьявления. Для мониторинга пошлите мне ссылку поиска olx c настроенными фильтрами \r\n\r\n" +
                                    "*Команды:*\r\n" +
                                    ACTIVE_LISTENERS_LINK +
                                    "*Инструкция:*\r\n" +
                                    "https://www.youtube.com/watch?v=cHwrh4QE4wE\r\n\r\n" +
                                    "Обратная связь и 🍎 - ntvf9999@gmail.com" +
                                    "\r\n⭐⭐⭐⭐⭐")

            ).build();
        }
        return HandleResult.EMPTY;
    }

    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    void notifySubscribedChats() {
        listenerStorage.getAllListeners().forEach(listener -> {
                    executors.submit(() -> processListener(listener));
                }
        );
    }

    private void processListener(Listener listener) {
        List<Offer> offers;
        try {
            offers = olxGrabber.getOffers(listener.getUrl());
        } catch (Exception e) {
            log.error("Exception while processing listener:{}", listener, e);
            offers = Collections.emptyList();
        }
        if (listener.getLastOffersHashes() == null) {
            listener.setLastOffersHashes(offers.stream().map(this::getHash).collect(Collectors.toSet()));
            listenerStorage.saveListener(listener);
        } else {
            for (Offer offer : offers) {
                String offerHash = getHash(offer);
                if (!listener.getLastOffersHashes().contains(offerHash)) {
                    listener.getLastOffersHashes().add(offerHash);
                    listenerStorage.saveListener(listener);
                    sendNotificationToChat(listener, offer);
                }
            }
        }
    }

    private void sendNotificationToChat(Listener listener, Offer offer) {
        HandleResult.HandleResultBuilder builder = HandleResult.builder();
        String text = offer.getName() + "\r\n"
                + offer.getUrl() + "\r\n";
        builder.botApiMethod(
                new SendMessage()
                        .enableMarkdown(false)
                        .setChatId(listener.getChatId())
                        .setText(text)
        );
        log.info("Going to send message:{} to chat:{}", text, listener.getChatId());
        processResults(builder.build());

    }

    private String getHash(Offer it) {
        return DigestUtils.md5Hex(it.getName() + it.getUrl());
    }
}
