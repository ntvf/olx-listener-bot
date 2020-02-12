package io.chatbots.olx.stats.impl;

import io.chatbots.olx.stats.BotStats;
import io.chatbots.olx.stats.BotStatsService;
import io.chatbots.olx.stats.UserLocaleStats;
import io.chatbots.olx.storage.entity.Listener;
import lombok.val;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jongo.Jongo;
import org.jongo.MongoCollection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.chatbots.olx.storage.impl.MongoListenerStorage.LISTENERS_COLLECTION;

public class MongoStatsService implements BotStatsService {

    private MongoCollection listeners;

    public MongoStatsService(Jongo jongo) {
        listeners = jongo.getCollection(LISTENERS_COLLECTION);
    }

    @Override
    public BotStats getBotStats() {
        val listenerIterator = listeners.find("{}").projection("{lastOffersHashes : 0}")
                .as(Listener.class)
                .iterator();

        long allListenersCount = 0;
        long activeListenersCount = 0;
        val allUsers = new HashSet<>();
        val activeUsers = new HashSet<>();
        val allUsersLocales = new HashSet<Pair<Long ,String>>();
        val activeUsersLocales = new HashSet<Pair<Long ,String>>();

        while (listenerIterator.hasNext()) {
            Listener listener = listenerIterator.next();
            allListenersCount++;
            if (listener.isActive()) {
                activeListenersCount++;
                activeUsers.add(getUserKey(listener));
                activeUsersLocales.add(new ImmutablePair(listener.getUserId(),listener.getUserLanguageCode()));
            }
            allUsers.add(getUserKey(listener));
            allUsersLocales.add(new ImmutablePair(listener.getUserId(),listener.getUserLanguageCode()));
        }

        return BotStats.builder()
                .allListenersCount(allListenersCount)
                .activeListenersCount(activeListenersCount)
                .allUsersCount(allUsers.size())
                .activeUsersCount(activeUsers.size())
                .allUsersLocales(UserLocaleStats.builder()
                        .localesCount(mapLocales(allUsersLocales))
                        .build())
                .activeUsersLocales(UserLocaleStats.builder()
                        .localesCount(mapLocales(activeUsersLocales))
                        .build())
                .build();
    }

    private Map<String, Integer> mapLocales(Set<Pair<Long,String>> allUsersLocales) {
        return allUsersLocales.stream().collect(Collectors.groupingBy(Pair::getRight))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, it -> it.getValue().size()));
    }

    private static String getUserKey(Listener listener) {
        return listener.getUserFirstName() + listener.getUserName() + listener.getUserId();
    }
}
