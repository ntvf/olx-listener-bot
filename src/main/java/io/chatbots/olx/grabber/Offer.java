package io.chatbots.olx.grabber;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Offer {
    private String url;
    private String name;
    private String content;
}
