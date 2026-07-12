package io.chatbots.olx;

import io.chatbots.olx.checker.RegressionChecker;
import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.OlxGrabber;
import io.chatbots.olx.i18n.TranslationService;
import io.chatbots.olx.stats.BotStats;
import io.chatbots.olx.stats.BotStatsService;
import io.chatbots.olx.stats.UserLocaleStats;
import io.chatbots.olx.storage.ListenerOfferHashRepository;
import io.chatbots.olx.storage.ListenerStorage;
import io.chatbots.olx.storage.entity.Listener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OlxTelegramBotCommandTest {

    @InjectMocks
    private OlxTelegramBot bot;

    @Mock
    private ListenerStorage listenerStorage;
    @Mock
    private BotStatsService botStatsService;
    @Mock
    private RegressionChecker regressionChecker;
    @Mock
    private OlxGrabber olxGrabber;
    @Mock
    private TranslationService translationService;
    @Mock
    private TelegramClient telegramClient;
    @Mock
    private ListenerOfferHashRepository hashRepository;
    @Mock
    private io.chatbots.olx.score.ScoreService scoreService;

    private void enableScoring() {
        org.springframework.test.util.ReflectionTestUtils.setField(bot, "aiScoreEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(bot, "scorePrefix", "score");
        org.springframework.test.util.ReflectionTestUtils.setField(bot, "scoreExecutor",
                new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, MILLISECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<>(30)));
    }

    @BeforeEach
    void stubButtonTranslations() {
        when(translationService.translate(eq("listeners.btn.close"), any(Locale.class))).thenReturn("❌ Close");
        when(translationService.translate(eq("listeners.btn.confirm.remove"), any(Locale.class))).thenReturn("✅ Yes, remove");
        when(translationService.translate(eq("listeners.btn.cancel"), any(Locale.class))).thenReturn("❌ Cancel");
        when(translationService.translate(eq("listeners.confirm.remove"), any(Locale.class))).thenReturn("Remove this listener?");
    }

    // ── update builders ───────────────────────────────────────────────────────

    private Update privateUpdate(String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        Chat chat = mock(Chat.class);
        User user = mock(User.class);

        when(update.hasMessage()).thenReturn(true);
        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.getMessage()).thenReturn(message);
        when(message.getChat()).thenReturn(chat);
        when(chat.getType()).thenReturn("private");
        when(message.getText()).thenReturn(text);
        when(message.getChatId()).thenReturn(12345L);
        when(message.getFrom()).thenReturn(user);
        when(message.getEntities()).thenReturn(Collections.emptyList());
        when(user.getId()).thenReturn(100L);
        when(user.getFirstName()).thenReturn("Test");
        when(user.getLastName()).thenReturn("User");
        when(user.getUserName()).thenReturn("testuser");
        when(user.getLanguageCode()).thenReturn("en");

        return update;
    }

    private Update callbackUpdate(String data, long chatId, int messageId) {
        Update update = mock(Update.class);
        CallbackQuery callbackQuery = mock(CallbackQuery.class);
        Message message = mock(Message.class);
        User user = mock(User.class);

        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);
        when(callbackQuery.getData()).thenReturn(data);
        when(callbackQuery.getId()).thenReturn("cb-id");
        when(callbackQuery.getMessage()).thenReturn(message);
        when(callbackQuery.getFrom()).thenReturn(user);
        when(message.getChatId()).thenReturn(chatId);
        when(message.getMessageId()).thenReturn(messageId);
        when(user.getLanguageCode()).thenReturn("en");

        return update;
    }

    // ── /start ────────────────────────────────────────────────────────────────

    @Test
    void start_sendsWelcomeMessage() throws Exception {
        when(translationService.translate(eq("bot.start"), any(Locale.class))).thenReturn("Welcome!");
        when(translationService.translate(eq("menu.listeners"), any(Locale.class))).thenReturn("Listeners");
        Update update = privateUpdate("/start");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
            verify(telegramClient).execute(captor.capture());
            SendMessage sent = (SendMessage) captor.getValue();
            assertThat(sent.getChatId()).isEqualTo("12345");
            assertThat(sent.getText()).isEqualTo("Welcome!");
        });
    }

    @Test
    void start_notTriggeredByOtherText() throws Exception {
        Update update = privateUpdate("hello");

        bot.consume(update);

        await().during(200, MILLISECONDS).atMost(500, MILLISECONDS)
                .untilAsserted(() -> verify(telegramClient, never()).execute(any(BotApiMethod.class)));
    }

    // ── add listener ──────────────────────────────────────────────────────────

    @Test
    void addListener_savesListenerAndConfirms() throws Exception {
        when(olxGrabber.supportsUrl("https://www.olx.pl/oferty/q-iphone/")).thenReturn(true);
        when(translationService.translate(eq("listeners.created"), any(Locale.class))).thenReturn("Saved!");
        Update update = privateUpdate("https://www.olx.pl/oferty/q-iphone/");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
            verify(listenerStorage).saveListener(listenerCaptor.capture());
            Listener saved = listenerCaptor.getValue();
            assertThat(saved.getUrl()).isEqualTo("https://www.olx.pl/oferty/q-iphone/");
            assertThat(saved.getChatId()).isEqualTo(12345L);
            assertThat(saved.getUserId()).isEqualTo(100L);
            assertThat(saved.isActive()).isTrue();

            ArgumentCaptor<BotApiMethod> msgCaptor = ArgumentCaptor.forClass(BotApiMethod.class);
            verify(telegramClient).execute(msgCaptor.capture());
            assertThat(((SendMessage) msgCaptor.getValue()).getText()).isEqualTo("Saved!");
        });
    }

    @Test
    void addListener_setsUserMetadataFromUpdate() {
        when(olxGrabber.supportsUrl(anyString())).thenReturn(true);
        when(translationService.translate(anyString(), any(Locale.class))).thenReturn("ok");
        Update update = privateUpdate("https://www.olx.bg/ads/q-phone/");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
            verify(listenerStorage).saveListener(captor.capture());
            Listener saved = captor.getValue();
            assertThat(saved.getUserFirstName()).isEqualTo("Test");
            assertThat(saved.getUserLastName()).isEqualTo("User");
            assertThat(saved.getUserName()).isEqualTo("testuser");
            assertThat(saved.getUserLanguageCode()).isEqualTo("en");
        });
    }

    @Test
    void addListener_rejectsWhenLimitReached() throws Exception {
        when(olxGrabber.supportsUrl(anyString())).thenReturn(true);
        when(translationService.translate(eq("listeners.limit.reached"), any(Locale.class))).thenReturn("Limit!");
        List<Listener> existing = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> Listener.builder().id((long) i).chatId(12345L).active(true).build())
                .collect(java.util.stream.Collectors.toList());
        when(listenerStorage.getChatListeners(12345L)).thenReturn(existing);
        Update update = privateUpdate("https://www.olx.pl/oferty/q-iphone/");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            verify(listenerStorage, never()).saveListener(any());
            ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
            verify(telegramClient).execute(captor.capture());
            assertThat(((SendMessage) captor.getValue()).getText()).isEqualTo("Limit!");
        });
    }

    @Test
    void addListener_ignoresTextWithoutUrl() throws Exception {
        Update update = privateUpdate("just some random text");

        bot.consume(update);

        await().during(200, MILLISECONDS).atMost(500, MILLISECONDS).untilAsserted(() -> {
            verify(listenerStorage, never()).saveListener(any());
            verify(telegramClient, never()).execute(any(BotApiMethod.class));
        });
    }

    @Test
    void addListener_rejectsUnsupportedUrl() throws Exception {
        when(olxGrabber.supportsUrl(anyString())).thenReturn(false);
        when(translationService.translate(eq("listeners.not.valid.url"), any(Locale.class))).thenReturn("Invalid!");
        Update update = privateUpdate("https://www.ebay.com/search?q=phone");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            verify(listenerStorage, never()).saveListener(any());
            ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
            verify(telegramClient).execute(captor.capture());
            assertThat(((SendMessage) captor.getValue()).getText()).isEqualTo("Invalid!");
        });
    }

    @Test
    void addListener_trimsLeadingGarbageBeforeUrl() throws Exception {
        when(olxGrabber.supportsUrl("https://www.olx.pl/oferty/q-iphone/")).thenReturn(true);
        when(translationService.translate(eq("listeners.created"), any(Locale.class))).thenReturn("Saved!");
        Update update = privateUpdate("   ​https://www.olx.pl/oferty/q-iphone/");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
            verify(listenerStorage).saveListener(captor.capture());
            assertThat(captor.getValue().getUrl()).isEqualTo("https://www.olx.pl/oferty/q-iphone/");
        });
    }

    @Test
    void addListener_extractsUrlFromTextWithTrailingContent() throws Exception {
        when(olxGrabber.supportsUrl("https://www.olx.pl/oferty/q-iphone/")).thenReturn(true);
        when(translationService.translate(anyString(), any(Locale.class))).thenReturn("ok");
        Update update = privateUpdate("check this out https://www.olx.pl/oferty/q-iphone/ nice deal");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
            verify(listenerStorage).saveListener(captor.capture());
            assertThat(captor.getValue().getUrl()).isEqualTo("https://www.olx.pl/oferty/q-iphone/");
        });
    }

    // ── score feature ─────────────────────────────────────────────────────────

    @Test
    void score_searchUrl_createsListenerWithScoringEnabled() throws Exception {
        enableScoring();
        when(olxGrabber.supportsUrl("https://www.olx.pl/oferty/q-lego/")).thenReturn(true);
        when(translationService.translate(eq("listeners.created"), any(Locale.class))).thenReturn("Saved!");
        Update update = privateUpdate("score https://www.olx.pl/oferty/q-lego/");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
            verify(listenerStorage).saveListener(captor.capture());
            assertThat(captor.getValue().isScore()).isTrue();
            assertThat(captor.getValue().getUrl()).isEqualTo("https://www.olx.pl/oferty/q-lego/");
        });
    }

    @Test
    void score_singleAdUrl_runsOneOffScoreWithoutCreatingListener() throws Exception {
        enableScoring();
        when(olxGrabber.supportsUrl(anyString())).thenReturn(true);
        when(scoreService.scoreListing(eq("https://www.olx.pl/d/oferta/lego-ID123.html"), any()))
                .thenReturn("SUMMARY");
        Update update = privateUpdate("score https://www.olx.pl/d/oferta/lego-ID123.html");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            verify(listenerStorage, never()).saveListener(any());
            ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
            verify(telegramClient, times(2)).execute(captor.capture());
            assertThat(((SendMessage) captor.getAllValues().get(1)).getText()).isEqualTo("SUMMARY");
        });
    }

    @Test
    void score_disabled_fallsThroughToAddListener() throws Exception {
        when(olxGrabber.supportsUrl(anyString())).thenReturn(true);
        when(translationService.translate(eq("listeners.created"), any(Locale.class))).thenReturn("Saved!");
        Update update = privateUpdate("score https://www.olx.pl/oferty/q-lego/");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
            verify(listenerStorage).saveListener(captor.capture());
            assertThat(captor.getValue().isScore()).isFalse();
        });
    }

    @Test
    void processListener_scoredListener_sendsSummaryAfterNotification() throws Exception {
        enableScoring();
        String knownHash = org.apache.commons.codec.digest.DigestUtils.md5Hex("https://old.url");
        when(hashRepository.findHashesByListenerId(1L)).thenReturn(new HashSet<>(Set.of(knownHash)));
        when(scoreService.scoreListing(eq("https://www.olx.pl/d/new.html"), any())).thenReturn("SUMMARY");

        Listener listener = Listener.builder()
                .id(1L).chatId(12345L).url("https://www.olx.pl/oferty/q-lego/")
                .userLanguageCode("en").score(true).build();
        Offer newOffer = Offer.builder().url("https://www.olx.pl/d/new.html").name("Lego").build();
        when(olxGrabber.getOffers(anyString())).thenReturn(List.of(newOffer));

        bot.processListener(listener);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
            verify(telegramClient, times(2)).execute(captor.capture());
            assertThat(((SendMessage) captor.getAllValues().get(1)).getText()).isEqualTo("SUMMARY");
        });
    }

    @Test
    void processListener_unscoredListener_noSummary() throws Exception {
        enableScoring();
        String knownHash = org.apache.commons.codec.digest.DigestUtils.md5Hex("https://old.url");
        when(hashRepository.findHashesByListenerId(1L)).thenReturn(new HashSet<>(Set.of(knownHash)));

        Listener listener = Listener.builder()
                .id(1L).chatId(12345L).url("https://www.olx.pl/oferty/q-lego/")
                .userLanguageCode("en").score(false).build();
        Offer newOffer = Offer.builder().url("https://www.olx.pl/d/new.html").name("Lego").build();
        when(olxGrabber.getOffers(anyString())).thenReturn(List.of(newOffer));

        bot.processListener(listener);

        await().during(300, MILLISECONDS).atMost(1, SECONDS).untilAsserted(() -> {
            verify(telegramClient, times(1)).execute(any(BotApiMethod.class));
            verify(scoreService, never()).scoreListing(anyString(), any());
        });
    }

    // ── /listeners ────────────────────────────────────────────────────────────

    @Test
    void listListeners_withNoListeners_sendsEmptyMessage() throws Exception {
        when(listenerStorage.getChatListeners(12345L)).thenReturn(Collections.emptyList());
        when(translationService.translate(eq("listeners.empty"), any(Locale.class))).thenReturn("No listeners");
        when(translationService.translate(eq("menu.listeners"), any(Locale.class))).thenReturn("Listeners");
        Update update = privateUpdate("/listeners");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
            verify(telegramClient).execute(captor.capture());
            assertThat(((SendMessage) captor.getValue()).getText()).contains("No listeners");
        });
    }

    @Test
    void listListeners_withListeners_sendsOneMessage() throws Exception {
        Listener l1 = Listener.builder().id(1L).url("https://www.olx.pl/q-a/").chatId(12345L).active(true).build();
        Listener l2 = Listener.builder().id(2L).url("https://www.olx.ua/q-b/").chatId(12345L).active(true).build();
        when(listenerStorage.getChatListeners(12345L)).thenReturn(List.of(l1, l2));
        when(translationService.translate(eq("menu.listeners"), any(Locale.class))).thenReturn("Listeners");
        Update update = privateUpdate("/listeners");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() ->
                verify(telegramClient, times(1)).execute(any(BotApiMethod.class)));
    }

    @Test
    void listListeners_messageContainsUrlsAndInlineRemoveButtons() throws Exception {
        Listener l = Listener.builder().id(5L).url("https://www.olx.pl/q-iphone/").chatId(12345L).active(true).build();
        when(listenerStorage.getChatListeners(12345L)).thenReturn(List.of(l));
        when(translationService.translate(eq("menu.listeners"), any(Locale.class))).thenReturn("Listeners");
        Update update = privateUpdate("/listeners");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
            verify(telegramClient).execute(captor.capture());
            SendMessage sent = (SendMessage) captor.getValue();
            assertThat(sent.getText()).contains("https://www.olx.pl/q-iphone/");
            assertThat(sent.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
            InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
            boolean hasRemoveButton = markup.getKeyboard().stream()
                    .flatMap(List::stream)
                    .anyMatch(btn -> btn.getCallbackData().equals("rc:5"));
            assertThat(hasRemoveButton).isTrue();
        });
    }

    // ── remove listener via callback ──────────────────────────────────────────

    @Test
    void removeListener_callbackConfirm_showsConfirmationKeyboard() throws Exception {
        Listener l = Listener.builder().id(42L).url("https://www.olx.pl/q-iphone/").chatId(12345L).active(true).build();
        when(listenerStorage.getChatListeners(12345L)).thenReturn(List.of(l));
        Update update = callbackUpdate("rc:42", 12345L, 99);

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            verify(telegramClient).execute(any(EditMessageText.class));
            verify(telegramClient).execute(any(AnswerCallbackQuery.class));
        });
    }

    @Test
    void removeListener_callbackDo_deletesListenerAndRerenderslist() throws Exception {
        when(listenerStorage.getChatListeners(12345L)).thenReturn(Collections.emptyList());
        when(translationService.translate(eq("listeners.empty"), any(Locale.class))).thenReturn("No listeners");
        Update update = callbackUpdate("rd:42", 12345L, 99);

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            verify(listenerStorage).deleteListener(42L, 12345L);
            verify(telegramClient).execute(any(EditMessageText.class));
            verify(telegramClient).execute(any(AnswerCallbackQuery.class));
        });
    }

    @Test
    void removeListener_callbackAbort_rerendersListUnchanged() throws Exception {
        Listener l = Listener.builder().id(42L).url("https://www.olx.pl/q-iphone/").chatId(12345L).active(true).build();
        when(listenerStorage.getChatListeners(12345L)).thenReturn(List.of(l));
        Update update = callbackUpdate("ra", 12345L, 99);

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            verify(listenerStorage, never()).deleteListener(any(Long.class), any(Long.class));
            verify(telegramClient).execute(any(EditMessageText.class));
            verify(telegramClient).execute(any(AnswerCallbackQuery.class));
        });
    }

    // ── /stats ────────────────────────────────────────────────────────────────

    @Test
    void stats_returnsFormattedCountsMessage() throws Exception {
        BotStats stats = BotStats.builder()
                .allListenersCount(10).activeListenersCount(7)
                .allUsersCount(5).activeUsersCount(4)
                .allUsersLocales(UserLocaleStats.builder().localesCount(Map.of("en", 3)).build())
                .activeUsersLocales(UserLocaleStats.builder().localesCount(Map.of("en", 2)).build())
                .build();
        when(botStatsService.getBotStats()).thenReturn(stats);
        when(regressionChecker.getLastResults()).thenReturn(Collections.emptyMap());
        Update update = privateUpdate("/stats");

        bot.consume(update);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
            verify(telegramClient).execute(captor.capture());
            String text = ((SendMessage) captor.getValue()).getText();
            assertThat(text).contains("10");
            assertThat(text).contains("7");
            assertThat(text).contains("5");
            assertThat(text).contains("4");
        });
    }

    // ── processListener notification logic ────────────────────────────────────

    @Test
    void processListener_firstRun_seedsHashesWithoutNotifying() throws Exception {
        Listener listener = Listener.builder()
                .id(1L).chatId(12345L).url("https://www.olx.pl/q-iphone/")
                .userLanguageCode("en").build();
        Offer offer = Offer.builder().url("https://www.olx.pl/d/item/offer.html").name("iPhone 14").build();
        when(olxGrabber.getOffers(anyString())).thenReturn(List.of(offer));
        when(hashRepository.findHashesByListenerId(1L)).thenReturn(Collections.emptySet());

        bot.processListener(listener);

        verify(hashRepository).saveAll(argThat(list -> ((List<?>) list).size() == 1));
        verify(telegramClient, never()).execute(any(BotApiMethod.class));
    }

    @Test
    void processListener_newOffer_sendsNotificationAndSavesHash() throws Exception {
        String knownHash = org.apache.commons.codec.digest.DigestUtils.md5Hex("https://old.url");
        when(hashRepository.findHashesByListenerId(1L)).thenReturn(new HashSet<>(Set.of(knownHash)));
        when(translationService.translate(eq("listeners.updated"), any(Locale.class))).thenReturn("Updated:");

        Listener listener = Listener.builder()
                .id(1L).chatId(12345L).url("https://www.olx.pl/q-iphone/")
                .userLanguageCode("en").build();
        Offer newOffer = Offer.builder().url("https://www.olx.pl/d/new.html").name("iPhone 15").build();
        when(olxGrabber.getOffers(anyString())).thenReturn(List.of(newOffer));

        bot.processListener(listener);

        verify(telegramClient, times(1)).execute(any(BotApiMethod.class));
        verify(hashRepository).saveAll(argThat(list -> ((List<?>) list).size() == 1));
    }

    @Test
    void processListener_alreadySeenOffer_noNotificationNoSave() throws Exception {
        Offer offer = Offer.builder().url("https://www.olx.pl/d/seen.html").name("iPhone 13").build();
        String hash = org.apache.commons.codec.digest.DigestUtils.md5Hex(offer.getUrl());
        when(hashRepository.findHashesByListenerId(1L)).thenReturn(new HashSet<>(Set.of(hash)));

        Listener listener = Listener.builder()
                .id(1L).chatId(12345L).url("https://www.olx.pl/q-iphone/")
                .userLanguageCode("en").build();
        when(olxGrabber.getOffers(anyString())).thenReturn(List.of(offer));

        bot.processListener(listener);

        verify(telegramClient, never()).execute(any(BotApiMethod.class));
        verify(hashRepository, never()).saveAll(argThat(list -> !((List<?>) list).isEmpty()));
    }

    @Test
    void processListener_grabberThrows_handledGracefully() throws Exception {
        when(hashRepository.findHashesByListenerId(1L))
                .thenReturn(Set.of(org.apache.commons.codec.digest.DigestUtils.md5Hex("https://some.url")));
        Listener listener = Listener.builder()
                .id(1L).chatId(12345L).url("https://www.olx.pl/q-iphone/")
                .userLanguageCode("en").build();
        when(olxGrabber.getOffers(anyString())).thenThrow(new RuntimeException("timeout"));

        bot.processListener(listener);

        verify(telegramClient, never()).execute(any(BotApiMethod.class));
        verify(hashRepository, never()).saveAll(any());
    }

    @Test
    void processListener_emptyOffersOnFirstRun_noHashesSaved() throws Exception {
        when(hashRepository.findHashesByListenerId(1L)).thenReturn(Collections.emptySet());
        Listener listener = Listener.builder()
                .id(1L).chatId(12345L).url("https://www.olx.pl/q-iphone/")
                .userLanguageCode("en").build();
        when(olxGrabber.getOffers(anyString())).thenReturn(Collections.emptyList());

        bot.processListener(listener);

        verify(hashRepository, never()).saveAll(any());
        verify(telegramClient, never()).execute(any(BotApiMethod.class));
    }
}
