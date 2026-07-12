package io.chatbots.olx.score;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ListingInfo {
    private String url;
    private String title;
    private String price;
    private String location;
    private String description;
}
