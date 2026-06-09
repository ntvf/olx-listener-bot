package io.chatbots.olx.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class HashCleanupService {

    private final ListenerOfferHashRepository hashRepository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void removeExpiredHashes() {
        Instant threshold = Instant.now().minus(365, ChronoUnit.DAYS);
        int deleted = hashRepository.deleteByCreatedAtBefore(threshold);
        log.info("Removed {} expired offer hashes older than 1 year", deleted);
    }
}
