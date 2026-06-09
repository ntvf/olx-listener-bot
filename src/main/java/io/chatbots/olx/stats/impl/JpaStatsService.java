package io.chatbots.olx.stats.impl;

import io.chatbots.olx.stats.BotStats;
import io.chatbots.olx.stats.BotStatsService;
import io.chatbots.olx.stats.UserLocaleStats;
import io.chatbots.olx.storage.ListenerJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JpaStatsService implements BotStatsService {

    private final ListenerJpaRepository repo;

    @Override
    public BotStats getBotStats() {
        Object[] counts = repo.getAggregateCounts().get(0);
        long allListeners = ((Number) counts[0]).longValue();
        long activeListeners = ((Number) counts[1]).longValue();
        long allUsers = ((Number) counts[2]).longValue();
        long activeUsers = ((Number) counts[3]).longValue();

        Map<String, Integer> allLocales = new HashMap<>();
        Map<String, Integer> activeLocales = new HashMap<>();
        for (Object[] row : repo.getLocaleStats()) {
            String locale = (String) row[0];
            boolean active = (Boolean) row[1];
            int userCount = ((Number) row[2]).intValue();
            if (locale == null) locale = "unknown";
            if (active) {
                activeLocales.merge(locale, userCount, Integer::sum);
            } else {
                allLocales.merge(locale, userCount, Integer::sum);
            }
        }
        // allLocales should include active users too
        activeLocales.forEach((k, v) -> allLocales.merge(k, v, Integer::sum));

        return BotStats.builder()
                .allListenersCount(allListeners)
                .activeListenersCount(activeListeners)
                .allUsersCount(allUsers)
                .activeUsersCount(activeUsers)
                .allUsersLocales(UserLocaleStats.builder().localesCount(allLocales).build())
                .activeUsersLocales(UserLocaleStats.builder().localesCount(activeLocales).build())
                .build();
    }
}
