package io.chatbots.olx.stats;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BotStats {
    long activeListenersCount;
    long allListenersCount;
    long allUsersCount;
    long activeUsersCount;
    UserLocaleStats allUsersLocales;
    UserLocaleStats activeUsersLocales;

}
