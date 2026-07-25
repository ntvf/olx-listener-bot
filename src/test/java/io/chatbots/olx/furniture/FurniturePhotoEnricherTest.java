package io.chatbots.olx.furniture;

import io.chatbots.olx.furniture.entity.FurnitureFeed;
import io.chatbots.olx.furniture.entity.FurnitureOffer;
import io.chatbots.olx.score.CaptchaTunnelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The enricher must treat a transient block (unsolved CAPTCHA / wedged browser) as retryable —
 * leaving the offer on {@code dim_source='none'} — and only burn to {@code 'none_ai'} when the AI
 * genuinely answered without a size or the listing photo itself is unreachable.
 */
class FurniturePhotoEnricherTest {

    private final FurnitureOfferRepository offerRepository = mock(FurnitureOfferRepository.class);
    private final FurnitureFeedRepository feedRepository = mock(FurnitureFeedRepository.class);
    private final FurniturePhotoDimensionService photoService = mock(FurniturePhotoDimensionService.class);
    private final CaptchaTunnelService captchaTunnelService = mock(CaptchaTunnelService.class);
    private final TelegramClient telegramClient = mock(TelegramClient.class);

    /** A real, readable image URL so download() succeeds and the describe()/burn logic is what's under test. */
    private String imageUrl;

    @BeforeEach
    void createReadablePhoto() throws IOException {
        Path photo = Files.createTempFile("enricher-test-photo-", ".jpg");
        photo.toFile().deleteOnExit();
        Files.write(photo, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        imageUrl = photo.toUri().toString();
    }

    private FurniturePhotoEnricher enricher(int batchPerTick) {
        when(feedRepository.findById(anyLong()))
                .thenReturn(Optional.of(FurnitureFeed.builder().id(1L).adminChatId(42L).build()));
        return new FurniturePhotoEnricher(offerRepository, feedRepository, photoService,
                captchaTunnelService, telegramClient, true, batchPerTick, 0L);
    }

    private FurnitureOffer offer(long id) {
        return FurnitureOffer.builder().id(id).feedId(1L).model("KALLAX")
                .imageUrl(imageUrl).dimSource("none")
                .url("https://www.olx.pl/d/oferta/x-ID" + id + ".html").build();
    }

    private void givenCandidates(FurnitureOffer... offers) {
        when(offerRepository.findPhotoCandidates(any(Pageable.class))).thenReturn(List.of(offers));
    }

    @Test
    void captchaBlockLeavesOfferRetryable() {
        givenCandidates(offer(1));
        when(photoService.describe(any(), any()))
                .thenThrow(new IllegalStateException("CAPTCHA not solved in time"));

        enricher(2).enrichBatch();

        // Not burned: dim_source stays 'none' so a solved CAPTCHA on a later tick can still size it.
        verify(offerRepository, never()).save(any());
    }

    @Test
    void transientBlockStopsTheBatch() {
        givenCandidates(offer(1), offer(2));
        when(photoService.describe(any(), any()))
                .thenThrow(new IllegalStateException("CAPTCHA not solved in time"));

        enricher(2).enrichBatch();

        // The block is global — no point burning a second 240s wait on the next offer this tick.
        verify(photoService, times(1)).describe(any(), any());
    }

    @Test
    void aiAnswerWithoutSizeBurnsToNoneAi() {
        givenCandidates(offer(1));
        when(photoService.describe(any(), any()))
                .thenReturn("MODEL: unclear\nDIMS: unknown\nSOURCE: unknown");

        enricher(2).enrichBatch();

        FurnitureOffer saved = savedOffer();
        assertEquals("none_ai", saved.getDimSource());
        assertNull(saved.getVariant());
    }

    @Test
    void photoSizedOfferIsMarkedPhoto() {
        givenCandidates(offer(1));
        when(photoService.describe(any(), any()))
                .thenReturn("MODEL: KALLAX 2x4\nDIMS: 77x39x147 cm\nSOURCE: photo+catalog");

        enricher(2).enrichBatch();

        FurnitureOffer saved = savedOffer();
        assertEquals("photo", saved.getDimSource());
        assertEquals("W77", saved.getVariant());
    }

    private FurnitureOffer savedOffer() {
        ArgumentCaptor<FurnitureOffer> captor = ArgumentCaptor.forClass(FurnitureOffer.class);
        verify(offerRepository).save(captor.capture());
        return captor.getValue();
    }
}
