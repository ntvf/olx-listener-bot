package io.chatbots.olx;

import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.OlxGrabber;
import io.chatbots.olx.i18n.TranslationService;
import io.chatbots.olx.stats.BotStatsService;
import io.chatbots.olx.stats.UserLocaleStats;
import io.chatbots.olx.storage.ListenerStorage;
import io.chatbots.olx.storage.entity.Listener;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class OlxTelegramBot implements LongPollingSingleThreadUpdateConsumer {

    public static final String MARKDOWN_PARCE_MODE = "Markdown";
    private static final String REMOVE_PREFIX = "/r_";
    private static final String LISTENERS_COMMAND = "/listeners";
    private final ExecutorService executors = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4);
    @Autowired
    private ListenerStorage listenerStorage;
    @Autowired
    private BotStatsService botStatsService;
    @Autowired
    private OlxGrabber olxGrabber;
    @Autowired
    private TranslationService translationService;
    @Autowired
    private TelegramClient telegramClient;

    @PreDestroy
    public void destroy() {
        executors.shutdown();
    }

    @Override
    public void consume(Update update) {
        try {

            if ("private".equals(update.getMessage().getChat().getType())) {
                List<BooleanSupplier> handlers = registerHandlers(update);
                walkThrough(handlers);
            } else if ("group".equals(update.getMessage().getChat().getType())) {
                List<BooleanSupplier> handlers = new ArrayList<>();
                handlers.add(execute(this::start, update, true));
                handlers.add(execute(this::listListeners, update, true));
                handlers.add(execute(this::removeListener, update, true));
                handlers.add(execute(this::stats, update, true));
                handlers.add(execute(this::unknownCommand, update, true));
                if (Optional.ofNullable(update.getMessage().getEntities())
                        .orElse(Collections.emptyList())
                        .stream()
                        .anyMatch(it -> "mention".equals(it.getType()))) {
                    handlers.add(execute(this::addListener, update, true));
                }
                walkThrough(handlers);
            }
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
        handlers.add(execute(this::stats, update, true));
        handlers.add(execute(this::unknownCommand, update, true));

        return handlers;
    }

    private HandleResult unknownCommand(Update update) {
        return HandleResult.EMPTY;
    }

    private String getText(Update update) {
        val initial = update.getMessage().getText();
        return Optional.ofNullable(update.getMessage().getEntities())
                .orElse(Collections.emptyList())
                .stream()
                .filter(it -> "mention".equals(it.getType()))
                .findFirst()
                .map(MessageEntity::getText)
                .map(it -> StringUtils.strip(initial.replace(it, "")))
                .orElse(initial);

    }

    private HandleResult stats(Update update) {
        if ("/stats".equals(getText(update))) {
            val botStats = botStatsService.getBotStats();


            return HandleResult.builder().botApiMethod(
                    SendMessage.builder()
                            .chatId(update.getMessage().getChatId())
                            .text("all listeners: " + botStats.getAllListenersCount() + "\r\n" +
                                    "active listeners: " + botStats.getActiveListenersCount() + "\r\n" +
                                    "all users: " + botStats.getAllUsersCount() + "\r\n" +
                                    "active users: " + botStats.getActiveUsersCount() + "\r\n" +
                                    "users by language" + "\r\n" +
                                    "all: " + formatLocales(botStats.getAllUsersLocales()) + "\r\n" +
                                    "active: " + formatLocales(botStats.getActiveUsersLocales()))
                            .build()
            ).build();
        }
        return HandleResult.EMPTY;
    }

    private String formatLocales(UserLocaleStats localeStats) {
        return localeStats.getLocalesCount().entrySet()
                .stream().map(it -> it.getKey() + ":" + it.getValue())
                .collect(Collectors.joining(","));
    }

    @SneakyThrows
    private HandleResult addListener(Update update) {
        String url = getText(update);
        if (Strings.CI.contains(url, "http")) {
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
                    SendMessage.builder()
                            .chatId(update.getMessage().getChatId())
                            .text(translationService.translate("listeners.created", getLocale(update)))
                            .build()
            ).build();
        }

        return HandleResult.EMPTY;
    }

    private HandleResult removeListener(Update update) {
        if (getText(update).startsWith(REMOVE_PREFIX)) {
            String id = getText(update).split(REMOVE_PREFIX)[1];
            listenerStorage.deleteListener(id, update.getMessage().getChatId());
            return HandleResult.builder().botApiMethod(
                    SendMessage.builder()
                            .parseMode(MARKDOWN_PARCE_MODE)
                            .chatId(update.getMessage().getChatId())
                            .text(translationService.translate("listeners.removed", getLocale(update)))
                            .build()

            ).build();
        }
        return HandleResult.EMPTY;
    }

    private HandleResult listListeners(Update update) {
        if (!LISTENERS_COMMAND.equals(getText(update))) {
            return HandleResult.EMPTY;
        }
        HandleResult.HandleResultBuilder resultBuilder = HandleResult.builder();


        List<Listener> listeners = listenerStorage.getChatListeners(update.getMessage().getChatId());
        if (listeners.isEmpty()) {
            resultBuilder.botApiMethod(
                    SendMessage.builder()
                            .chatId(update.getMessage().getChatId())
                            .text(translationService.translate("listeners.empty", getLocale(update)) + "\r\n\r\n" +
                                    "/start")
                            .build()
            );
        } else {
            listeners
                    .forEach(listener -> resultBuilder.botApiMethod(
                            SendMessage.builder()
                                    .chatId(update.getMessage().getChatId())
                                    .text("URL:" +
                                            listener.getUrl() + "\r\n" +
                                            translationService.translate("listeners.remove", getLocale(update)) + " " + REMOVE_PREFIX + listener.getId())
                                    .build()
                            )

                    );
        }
        return resultBuilder.build();
    }

    private Locale getLocale(Update update) {
        return Optional.ofNullable(update.getMessage().getFrom())
                .map(User::getLanguageCode)
                .map(Locale::forLanguageTag)
                .orElse(Locale.US);
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
            telegramClient.execute(method);
        }
        for (SendDocument document : handleResult.getSendDocuments()) {
            telegramClient.execute(document);
        }
        handleResult.getCallBack().run();
        if (handleResult != HandleResult.EMPTY) {
            return true;
        } else {
            return false;
        }
    }

    private HandleResult start(Update update) {
        if ("/start".equals(getText(update))) {
            List<KeyboardRow> keyboard = new ArrayList<>();
            ReplyKeyboardMarkup replyKeyboardMarkup = new ReplyKeyboardMarkup(keyboard);
            replyKeyboardMarkup.setSelective(true);
            replyKeyboardMarkup.setResizeKeyboard(true);
            replyKeyboardMarkup.setOneTimeKeyboard(false);

            KeyboardRow keyboardFirstRow = new KeyboardRow();
            keyboardFirstRow.add(LISTENERS_COMMAND);
            keyboard.add(keyboardFirstRow);
            replyKeyboardMarkup.setKeyboard(keyboard);
            return HandleResult.builder().botApiMethod(
                    SendMessage.builder()
                            .parseMode(MARKDOWN_PARCE_MODE)
                            .chatId(update.getMessage().getChatId())
                            .replyMarkup(replyKeyboardMarkup)
                            .text(translationService.translate("bot.start", getLocale(update)))
                            .build()
            ).build();
        }
        return HandleResult.EMPTY;
    }

    public void notifySubscribedChats() {
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
                SendMessage.builder()
                        .chatId(listener.getChatId())
                        .text(text)
                        .build()
        );
        log.info("Going to send message:{} to chat:{}", text, listener.getChatId());
        processResults(builder.build());

    }

    private String getHash(Offer it) {
        return DigestUtils.md5Hex(it.getName() + it.getUrl());
    }
}
