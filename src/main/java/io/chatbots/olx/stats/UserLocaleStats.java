package io.chatbots.olx.stats;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class UserLocaleStats {
    Map<String, Integer> localesCount;
}
