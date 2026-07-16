package io.chatbots.olx.score;

import lombok.Builder;
import lombok.Data;

/** What comparable used units of an item currently sell for, as reported by AI Mode. */
@Data
@Builder
public class MarketPrice {
    private double low;
    private double high;
    private String currency;
    /** How many comparable listings the answer claims to have found; 0 means "not enough data". */
    private int comparables;
    private String note;
}
