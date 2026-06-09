package io.chatbots.olx.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "listener_offer_hashes", indexes = {
        @Index(name = "idx_loh_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ListenerOfferHash {

    @EmbeddedId
    private Id id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ListenerOfferHash(long listenerId, String hash, Instant createdAt) {
        this.id = new Id(listenerId, hash);
        this.createdAt = createdAt;
    }

    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {
        @Column(name = "listener_id")
        private long listenerId;

        @Column(name = "hash", length = 64)
        private String hash;
    }
}
