package io.chatbots.olx.stats;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BotStats {
    private long activeListenersCount;
    private long allListenersCount;
    private long allUsersCount;
    private long activeUsersCount;
    private UserLocaleStats allUsersLocales;
    private UserLocaleStats activeUsersLocales;

}
