package io.chatbots.olx.furniture;

import io.chatbots.olx.furniture.entity.FurnitureFeed;
import io.chatbots.olx.furniture.entity.FurnitureOffer;
import io.chatbots.olx.score.AiModeSearchService;
import io.chatbots.olx.score.CaptchaTunnelService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fills the median <b>variant</b> for whole units the text parser could not size (the ~40%), by
 * running their photo through {@link FurniturePhotoDimensionService}. Deliberately decoupled from
 * publishing: it runs on its own single thread, so a slow or CAPTCHA-blocked AI call never delays a
 * burst — a confident, text-sized listing posts on schedule regardless. Each offer is attempted at
 * most once (marked {@code photo} on success, {@code none_ai} otherwise); either way it can still
 * post on the bare-model median. The CAPTCHA prompt goes to the chat that ran {@code /ikea-link}.
 */
@Slf4j
public class FurniturePhotoEnricher {

    private final FurnitureOfferRepository offerRepository;
    private final FurnitureFeedRepository feedRepository;
    private final FurniturePhotoDimensionService photoService;
    private final CaptchaTunnelService captchaTunnelService;
    private final TelegramClient telegramClient;
    private final boolean enabled;
    private final int batchPerTick;
    /** Operator DM used for the CAPTCHA when a feed predates admin_chat_id (0 = none). */
    private final long fallbackAdminChatId;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "furniture-photo-enricher");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FurniturePhotoEnricher(FurnitureOfferRepository offerRepository,
                                  FurnitureFeedRepository feedRepository,
                                  FurniturePhotoDimensionService photoService,
                                  CaptchaTunnelService captchaTunnelService,
                                  TelegramClient telegramClient,
                                  boolean enabled, int batchPerTick, long fallbackAdminChatId) {
        this.offerRepository = offerRepository;
        this.feedRepository = feedRepository;
        this.photoService = photoService;
        this.captchaTunnelService = captchaTunnelService;
        this.telegramClient = telegramClient;
        this.enabled = enabled;
        this.batchPerTick = batchPerTick;
        this.fallbackAdminChatId = fallbackAdminChatId;
    }

    /** Scheduler entry point: hands off to the worker thread and returns at once (never blocks polling/publishing). */
    public void tick() {
        if (!enabled) return;
        if (!running.compareAndSet(false, true)) return; // previous batch still in flight
        worker.submit(() -> {
            try {
                enrichBatch();
            } catch (Exception e) {
                log.error("Furniture photo enrichment batch failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    void enrichBatch() {
        List<FurnitureOffer> candidates = offerRepository.findPhotoCandidates(PageRequest.of(0, batchPerTick));
        for (FurnitureOffer offer : candidates) {
            if (!enrichOne(offer)) {
                // Transient block (unsolved CAPTCHA / wedged browser) — and it's global, not about this
                // one offer. Stop the batch: the offer stays dim_source='none' so a solved CAPTCHA on a
                // later tick can still size it, instead of being burned to 'none_ai' on a single miss.
                break;
            }
        }
    }

    /**
     * @return {@code true} once the offer reaches a terminal size decision (marked {@code photo} or
     * {@code none_ai}); {@code false} on a transient block, leaving it {@code none} to retry next tick.
     */
    private boolean enrichOne(FurnitureOffer offer) {
        // Per-feed chat when known (captured at /ikea-link); else the global operator DM, so feeds
        // linked before admin_chat_id existed still get their CAPTCHA routed without a DB edit.
        Long adminChatId = feedRepository.findById(offer.getFeedId())
                .map(FurnitureFeed::getAdminChatId)
                .orElse(fallbackAdminChatId != 0 ? fallbackAdminChatId : null);
        Path image;
        try {
            image = download(offer.getImageUrl());
        } catch (Exception e) {
            // The listing's own photo is unreachable; re-querying will not help — burn it once.
            log.warn("Photo unreachable for offer {} ({}), leaving on model median",
                    offer.getId(), offer.getUrl(), e);
            mark(offer, null, null, "none_ai");
            return true;
        }
        try {
            String answer = photoService.describe(image, event -> notifyCaptcha(adminChatId, event));
            Optional<FurnitureVariantParser.Variant> variant = FurnitureAiAnswerParser.fromAiAnswer(answer);
            if (variant.isPresent()) {
                mark(offer, variant.get().label(), variant.get().primaryDimCm(), "photo");
                log.info("Photo-sized offer {} as {} ({})", offer.getId(), variant.get().label(), offer.getModel());
            } else {
                mark(offer, null, null, "none_ai"); // AI answered but gave no size — permanent, do not re-query
            }
            return true;
        } catch (Exception e) {
            // CAPTCHA not solved in time or a wedged browser: transient and usually global. Leave the
            // offer on dim_source='none' so it is retried once access recovers, rather than lost forever.
            log.warn("Photo enrich blocked for offer {} ({}); will retry, staying on model median",
                    offer.getId(), offer.getUrl(), e);
            return false;
        } finally {
            captchaTunnelService.close();
            deleteQuietly(image);
        }
    }

    private void deleteQuietly(Path image) {
        try {
            Files.deleteIfExists(image);
        } catch (Exception e) {
            log.debug("temp photo cleanup failed for {}", image, e);
        }
    }

    private void mark(FurnitureOffer offer, String variant, Integer primaryDim, String source) {
        offer.setVariant(variant);
        offer.setPrimaryDim(primaryDim);
        offer.setDimSource(source);
        offerRepository.save(offer);
    }

    /** Route the CAPTCHA challenge to the operator chat that linked the feed (never the channel). */
    private void notifyCaptcha(Long adminChatId, AiModeSearchService.CaptchaEvent event) {
        if (adminChatId == null) {
            log.warn("AI Mode CAPTCHA needed but the feed has no admin chat to notify");
            return;
        }
        int minutes = Math.max(1, event.getWaitSeconds() / 60);
        String url = captchaTunnelService.openAccessUrl();
        String text = StringUtils.isBlank(url)
                ? "🧩 IKEA photo check needs a CAPTCHA but remote access to the browser could not be opened"
                    + " — solve it on the server display within " + minutes + " min."
                : "🧩 IKEA photo check needs a CAPTCHA. Open " + url + " , solve it, and I'll continue"
                    + " automatically (waiting up to " + minutes + " min).";
        send(adminChatId, text);
    }

    private Path download(String imageUrl) throws Exception {
        Path tmp = Files.createTempFile("furniture-photo-", ".jpg");
        try (InputStream in = URI.create(imageUrl).toURL().openStream()) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
    }

    private void send(long chatId, String text) {
        try {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (Exception e) {
            log.warn("Failed to send CAPTCHA prompt to {}", chatId, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }
}
