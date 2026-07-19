package io.chatbots.olx.grabber;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;

@Data
@Builder
public class Offer {
    private String url;
    private String name;
    private String content;
    private boolean promoted;
    private LocalDateTime updatedAt;
    private Instant createdAt;
}
