package io.chatbots.olx.furniture.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One IKEA-model search linked to a Telegram channel — the furniture counterpart of
 * {@code ChannelFeed}, kept in its own table so the rental funnel is never affected.
 */
@Entity
@Table(name = "furniture_feeds")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FurnitureFeed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_chat_id", nullable = false)
    private long channelChatId;

    /** One broad "q=ikea + warszawa" search; each listing's model is detected in code, not pinned here. */
    @Column(name = "feed_url", nullable = false, length = 2048)
    private String feedUrl;

    @Column(length = 64)
    private String label;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
