package io.chatbots.olx.storage;

import io.chatbots.olx.storage.entity.ListenerOfferHash;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

public interface ListenerOfferHashRepository extends JpaRepository<ListenerOfferHash, ListenerOfferHash.Id> {

    @Query("SELECT h.id.hash FROM ListenerOfferHash h WHERE h.id.listenerId = :listenerId")
    Set<String> findHashesByListenerId(@Param("listenerId") long listenerId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ListenerOfferHash h WHERE h.id.listenerId = :listenerId")
    void deleteByListenerId(@Param("listenerId") long listenerId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ListenerOfferHash h WHERE h.createdAt < :threshold")
    int deleteByCreatedAtBefore(@Param("threshold") Instant threshold);
}
