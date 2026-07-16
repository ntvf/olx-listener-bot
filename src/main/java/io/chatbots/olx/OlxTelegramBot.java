package io.chatbots.olx;

import io.chatbots.olx.checker.RegressionChecker;
import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.OlxGrabber;
import io.chatbots.olx.i18n.TranslationService;
import io.chatbots.olx.score.ScoreService;
import io.chatbots.olx.stats.BotStatsService;
import io.chatbots.olx.stats.UserLocaleStats;
import io.chatbots.olx.storage.ListenerOfferHashRepository;
import io.chatbots.olx.storage.ListenerStorage;
import io.chatbots.olx.storage.entity.Listener;
import io.chatbots.olx.storage.entity.ListenerOfferHash;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberUpdated;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class OlxTelegramBot implements LongPollingSingleThreadUpdateConsumer {

    public static final String MARKDOWN_PARCE_MODE = "Markdown";
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String LISTENERS_COMMAND = "/listeners";
    private static final String CB_REMOVE_CONFIRM = "rc:";
    private static final String CB_REMOVE_DO = "rd:";
    private static final String CB_REMOVE_ABORT = "ra";
    private static final String CB_CLOSE = "cl";
    private static final int MAX_LISTENERS_PER_CHAT = 10;
    private static final int MAX_QUEUED_SCORES = 30;
    // path fragments of individual ad pages (vs search/category pages) across supported sites
    private static final String[] SINGLE_AD_PATH_MARKERS = {"/d/", "/adv/", "/artikal/", "/item/", "/obyavlenie/", "/oferta/"};
    private final ExecutorService updateExecutor = Executors.newCachedThreadPool();
    @Value("${bot.listener.threads:20}")
    private int listenerThreads;
    private ExecutorService executors;
    private ThreadPoolExecutor scoreExecutor;

    @Autowired
    private ListenerStorage listenerStorage;
    @Autowired
    private BotStatsService botStatsService;
    @Autowired
    private RegressionChecker regressionChecker;
    @Autowired
    private OlxGrabber olxGrabber;
    @Autowired
    private TranslationService translationService;
    @Autowired
    private TelegramClient telegramClient;
    @Autowired
    private ListenerOfferHashRepository hashRepository;
    @Autowired
    private ScoreService scoreService;
    @Value("${ai.score.enabled:true}")
    private boolean aiScoreEnabled;
    // the "code word" gating the score feature; deployments should override the default
    @Value("${ai.score.prefix:score}")
    private String scorePrefix;

    private static String extractUrl(String text) {
        if (text == null) return null;
        int httpIdx = text.toLowerCase().indexOf("http");
        if (httpIdx < 0) return null;
        String fromHttp = text.substring(httpIdx);
        int end = fromHttp.length();
        for (int i = 0; i < fromHttp.length(); i++) {
            if (Character.isWhitespace(fromHttp.charAt(i))) {
                end = i;
                break;
            }
        }
        String url = fromHttp.substring(0, end).trim();
        return url.isEmpty() ? null : url;
    }

    @PostConstruct
    public void init() {
        executors = Executors.newFixedThreadPool(listenerThreads);
        // single worker: AI Mode queries are serialized anyway; bounded queue protects Google quota
        scoreExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(MAX_QUEUED_SCORES));
        log.info("Listener executor pool size: {}", listenerThreads);
    }

    @PreDestroy
    public void destroy() {
        executors.shutdown();
        updateExecutor.shutdown();
        if (scoreExecutor != null) scoreExecutor.shutdown();
    }

    @Override
    public void consume(Update update) {
        try {
            updateExecutor.submit(() -> processUpdate(update));
        } catch (Exception e) {
            // Must not propagate — any exception here permanently kills the BotSession polling task
            log.error("Failed to submit update to executor, updateId={}", update.getUpdateId(), e);
        }
    }

    private void processUpdate(Update update) {
        log.debug("Processing update id={} on thread={}", update.getUpdateId(), Thread.currentThread().getName());
        try {
            if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
                return;
            }
            if (update.hasMyChatMember()) {
                handleMyChatMember(update.getMyChatMember());
                return;
            }
            if (!update.hasMessage()) return;

            if ("private".equals(update.getMessage().getChat().getType())) {
                List<BooleanSupplier> handlers = registerHandlers(update);
                walkThrough(handlers);
            } else if ("group".equals(update.getMessage().getChat().getType())
                    || "supergroup".equals(update.getMessage().getChat().getType())) {
                List<BooleanSupplier> handlers = new ArrayList<>();
                handlers.add(execute(this::start, update, true));
                handlers.add(execute(this::listListeners, update, true));
                handlers.add(execute(this::stats, update, true));
                handlers.add(execute(this::scoreListing, update, true));
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

    @SneakyThrows(TelegramApiException.class)
    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        String data = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();
        int messageId = callbackQuery.getMessage().getMessageId();
        Locale locale = getLocale(callbackQuery.getFrom().getLanguageCode());

        if (data.startsWith(CB_REMOVE_CONFIRM)) {
            long listenerId = Long.parseLong(data.substring(CB_REMOVE_CONFIRM.length()));
            String url = listenerStorage.getChatListeners(chatId).stream()
                    .filter(l -> l.getId() == listenerId)
                    .map(Listener::getUrl)
                    .findFirst().orElse("?");
            telegramClient.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(translationService.translate("listeners.confirm.remove", locale) + "\n\n" + url)
                    .replyMarkup(buildConfirmationKeyboard(listenerId, locale))
                    .build());

        } else if (data.startsWith(CB_REMOVE_DO)) {
            long listenerId = Long.parseLong(data.substring(CB_REMOVE_DO.length()));
            listenerStorage.deleteListener(listenerId, chatId);
            List<Listener> updated = listenerStorage.getChatListeners(chatId);
            telegramClient.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(buildListenersText(updated, locale))
                    .replyMarkup(buildListenersKeyboard(updated, locale))
                    .build());

        } else if (CB_REMOVE_ABORT.equals(data)) {
            List<Listener> listeners = listenerStorage.getChatListeners(chatId);
            telegramClient.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(buildListenersText(listeners, locale))
                    .replyMarkup(buildListenersKeyboard(listeners, locale))
                    .build());

        } else if (CB_CLOSE.equals(data)) {
            telegramClient.execute(DeleteMessage.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .build());
        }

        telegramClient.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQuery.getId())
                .build());
    }

    private void handleMyChatMember(ChatMemberUpdated event) {
        String status = event.getNewChatMember().getStatus();
        if ("kicked".equals(status) || "left".equals(status)) {
            listenerStorage.deactivateChatListeners(event.getChat().getId());
            log.info("Deactivated listeners for chat:{} due to bot status:{}", event.getChat().getId(), status);
        }
    }

    private String buildListenersText(List<Listener> listeners, Locale locale) {
        if (listeners.isEmpty()) {
            return translationService.translate("listeners.empty", locale) + "\n\n/start";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < listeners.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(i + 1).append(". ");
            if (listeners.get(i).isScore()) sb.append("🤖 ");
            sb.append(listeners.get(i).getUrl());
        }
        return sb.toString();
    }

    private List<BooleanSupplier> registerHandlers(Update update) {
        List<BooleanSupplier> handlers = new ArrayList<>();
        handlers.add(execute(this::start, update, true));
        handlers.add(execute(this::listListeners, update, true));
        // must run before addListener, which would otherwise register the scored URL as a listener
        handlers.add(execute(this::scoreListing, update, true));
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

    private InlineKeyboardMarkup buildListenersKeyboard(List<Listener> listeners, Locale locale) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow current = new InlineKeyboardRow();
        for (int i = 0; i < listeners.size(); i++) {
            current.add(InlineKeyboardButton.builder()
                    .text("🗑 " + (i + 1))
                    .callbackData(CB_REMOVE_CONFIRM + listeners.get(i).getId())
                    .build());
            if (current.size() == 4) {
                rows.add(current);
                current = new InlineKeyboardRow();
            }
        }
        if (!current.isEmpty()) rows.add(current);
        InlineKeyboardRow closeRow = new InlineKeyboardRow();
        closeRow.add(InlineKeyboardButton.builder()
                .text(translationService.translate("listeners.btn.close", locale))
                .callbackData(CB_CLOSE).build());
        rows.add(closeRow);
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String formatLocales(UserLocaleStats localeStats) {
        return localeStats.getLocalesCount().entrySet()
                .stream().map(it -> it.getKey() + ":" + it.getValue())
                .collect(Collectors.joining(","));
    }

    private InlineKeyboardMarkup buildConfirmationKeyboard(long listenerId, Locale locale) {
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder()
                .text(translationService.translate("listeners.btn.confirm.remove", locale))
                .callbackData(CB_REMOVE_DO + listenerId).build());
        row.add(InlineKeyboardButton.builder()
                .text(translationService.translate("listeners.btn.cancel", locale))
                .callbackData(CB_REMOVE_ABORT).build());
        return InlineKeyboardMarkup.builder().keyboard(List.of(row)).build();
    }

    private HandleResult stats(Update update) {
        if ("/stats".equals(getText(update))) {
            val botStats = botStatsService.getBotStats();
            StringBuilder text = new StringBuilder();
            text.append("all listeners: ").append(botStats.getAllListenersCount()).append("\r\n");
            text.append("active listeners: ").append(botStats.getActiveListenersCount()).append("\r\n");
            text.append("all users: ").append(botStats.getAllUsersCount()).append("\r\n");
            text.append("active users: ").append(botStats.getActiveUsersCount()).append("\r\n");
            text.append("users by language\r\n");
            text.append("all: ").append(formatLocales(botStats.getAllUsersLocales())).append("\r\n");
            text.append("active: ").append(formatLocales(botStats.getActiveUsersLocales())).append("\r\n");
            text.append("\r\nparsers:\r\n");
            Map<String, Boolean> regressionResults = regressionChecker.getLastResults();
            if (regressionResults.isEmpty()) {
                text.append("not checked yet");
            } else {
                regressionResults.forEach((url, ok) -> {
                    String status = ok == null ? "?" : (ok ? "✓" : "✗");
                    text.append(status).append(" ").append(url).append("\r\n");
                });
            }
            return HandleResult.builder().botApiMethod(
                    SendMessage.builder()
                            .chatId(update.getMessage().getChatId())
                            .text(text.toString())
                            .build()
            ).build();
        }
        return HandleResult.EMPTY;
    }

    @SneakyThrows
    private HandleResult addListener(Update update) {
        String raw = getText(update);
        String url = extractUrl(raw);
        if (url == null) return HandleResult.EMPTY;
        return createListener(update, url, false);
    }

    @SneakyThrows
    private HandleResult createListener(Update update, String url, boolean score) {
        long chatId = update.getMessage().getChatId();
        if (!olxGrabber.supportsUrl(url)) {
            return HandleResult.builder().botApiMethod(
                    SendMessage.builder()
                            .chatId(chatId)
                            .text(translationService.translate("listeners.not.valid.url", getLocale(update)))
                            .build()
            ).build();
        }
        if (listenerStorage.getChatListeners(chatId).size() >= MAX_LISTENERS_PER_CHAT) {
            return HandleResult.builder().botApiMethod(
                    SendMessage.builder()
                            .chatId(chatId)
                            .text(translationService.translate("listeners.limit.reached", getLocale(update)))
                            .build()
            ).build();
        }
        User user = update.getMessage().getFrom();
        val newListener = Listener.builder()
                .userId(user.getId())
                .chatId(chatId)
                .userFirstName(user.getFirstName())
                .userLastName(user.getLastName())
                .userName(user.getUserName())
                .userLanguageCode(user.getLanguageCode())
                .updated(new Date())
                .url(url)
                .active(true)
                .score(score)
                .build();
        listenerStorage.saveListener(newListener);
        String confirmation = translationService.translate("listeners.created", getLocale(update));
        if (score) {
            confirmation += "\n🤖 AI flip-score enabled — every new find will be scored.";
        }
        return HandleResult.builder().botApiMethod(
                SendMessage.builder()
                        .chatId(chatId)
                        .text(confirmation)
                        .build()
        ).build();
    }

    /**
     * Hidden feature, no menu entry — only people who know the prefix can use it.
     * "score https://<ad-url>" scores one listing; "score https://<search-url>" creates a
     * listener whose every new find gets an AI flip-score follow-up.
     */
    private HandleResult scoreListing(Update update) {
        String text = getText(update);
        if (!aiScoreEnabled || text == null
                || !text.toLowerCase(Locale.ROOT).startsWith(scorePrefix.toLowerCase(Locale.ROOT) + " http")) {
            return HandleResult.EMPTY;
        }
        String url = extractUrl(text);
        if (url == null) return HandleResult.EMPTY;

        if (!isSingleAdUrl(url) && olxGrabber.supportsUrl(url)) {
            return createListener(update, url, true);
        }

        long chatId = update.getMessage().getChatId();
        sendPlainText(chatId, "🔎 Checking the deal, this can take a minute or two…");
        // Blocking is fine here: we're on the updateExecutor pool, and AI queries are serialized anyway
        String summary = scoreService.scoreListing(url, getLocale(update), progress -> sendPlainText(chatId, progress));
        return HandleResult.builder().botApiMethod(
                SendMessage.builder()
                        .chatId(chatId)
                        .text(summary)
                        .build()
        ).build();
    }

    private static boolean isSingleAdUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String marker : SINGLE_AD_PATH_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private void sendPlainText(long chatId, String text) {
        try {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) {
            log.warn("Failed to send message to chat:{}", chatId, e);
        }
    }

    private HandleResult listListeners(Update update) {
        String text = getText(update);
        String menuLabel = "📋 " + translationService.translate("menu.listeners", getLocale(update));
        if (!LISTENERS_COMMAND.equals(text) && !menuLabel.equals(text)) {
            return HandleResult.EMPTY;
        }
        long chatId = update.getMessage().getChatId();
        Locale locale = getLocale(update);
        List<Listener> listeners = listenerStorage.getChatListeners(chatId);

        return HandleResult.builder().botApiMethod(
                SendMessage.builder()
                        .chatId(chatId)
                        .text(buildListenersText(listeners, locale))
                        .replyMarkup(buildListenersKeyboard(listeners, locale))
                        .build()
        ).build();
    }

    private Locale getLocale(Update update) {
        return Optional.ofNullable(update.getMessage().getFrom())
                .map(User::getLanguageCode)
                .map(this::getLocale)
                .orElse(Locale.US);
    }

    private Locale getLocale(String languageCode) {
        return Optional.ofNullable(languageCode)
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
    private boolean processResults(HandleResult handleResult) {
        for (BotApiMethod method : handleResult.getBotApiMethods()) {
            telegramClient.execute(method);
        }
        for (SendDocument document : handleResult.getSendDocuments()) {
            telegramClient.execute(document);
        }
        handleResult.getCallBack().run();
        return handleResult != HandleResult.EMPTY;
    }

    private HandleResult start(Update update) {
        if ("/start".equals(getText(update))) {
            KeyboardRow row = new KeyboardRow();
            row.add("📋 " + translationService.translate("menu.listeners", getLocale(update)));
            ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup(List.of(row));
            keyboard.setResizeKeyboard(true);
            keyboard.setOneTimeKeyboard(false);
            return HandleResult.builder().botApiMethod(
                    SendMessage.builder()
                            .parseMode(MARKDOWN_PARCE_MODE)
                            .chatId(update.getMessage().getChatId())
                            .replyMarkup(keyboard)
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

    void processListener(Listener listener) {
        List<Offer> offers;
        try {
            offers = olxGrabber.getOffers(listener.getUrl());
        } catch (Exception e) {
            log.error("Exception while processing listener:{}", listener, e);
            offers = Collections.emptyList();
        }

        Set<String> knownHashes = hashRepository.findHashesByListenerId(listener.getId());
        boolean firstRun = knownHashes.isEmpty();

        List<ListenerOfferHash> toSave = new ArrayList<>();
        List<Offer> toNotify = new ArrayList<>();
        for (Offer offer : offers) {
            String hash = getHash(offer);
            if (!knownHashes.contains(hash)) {
                toSave.add(new ListenerOfferHash(listener.getId(), hash, Instant.now()));
                if (!firstRun) {
                    toNotify.add(offer);
                }
            }
        }
        if (!toSave.isEmpty()) {
            hashRepository.saveAll(toSave);
        }
        for (Offer offer : toNotify) {
            try {
                sendNotificationToChat(listener, offer);
                if (listener.isScore()) {
                    enqueueScore(listener, offer);
                }
            } catch (Exception e) {
                log.warn("Failed to notify offer:{} listener:{}", offer.getUrl(), listener.getId(), e);
            }
        }
    }

    private void enqueueScore(Listener listener, Offer offer) {
        if (!aiScoreEnabled || scoreExecutor == null) return;
        try {
            scoreExecutor.submit(() -> {
                try {
                    String summary = scoreService.scoreListing(offer.getUrl(),
                            getLocale(listener.getUserLanguageCode()),
                            progress -> sendPlainText(listener.getChatId(), progress));
                    sendPlainText(listener.getChatId(), summary);
                } catch (Exception e) {
                    log.warn("Scoring failed for offer:{}", offer.getUrl(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Score queue full ({}), skipping offer:{}", MAX_QUEUED_SCORES, offer.getUrl());
            sendPlainText(listener.getChatId(), "⚠️ AI scoring queue is full, skipped: " + offer.getUrl());
        }
    }

    private void sendNotificationToChat(Listener listener, Offer offer) {
        HandleResult.HandleResultBuilder builder = HandleResult.builder();
        String text = Optional.ofNullable(offer.getUpdatedAt())
                .map(it -> getUpdatedText(listener, it) + "\r\n")
                .orElse("")
                + offer.getName() + "\r\n"
                + offer.getUrl() + "\r\n";
        builder.botApiMethod(
                SendMessage.builder()
                        .chatId(listener.getChatId())
                        .text(text)
                        .build()
        );
        log.debug("Going to send message:{} to chat:{}", text, listener.getChatId());
        processResults(builder.build());
    }

    private @NotNull String getUpdatedText(Listener listener, LocalDateTime it) {
        return translationService.translate("listeners.updated", getLocale(listener.getUserLanguageCode())) + " " + it.format(DATE_TIME_FORMATTER);
    }

    private String getHash(Offer it) {
        return DigestUtils.md5Hex(it.getUrl());
    }
}
