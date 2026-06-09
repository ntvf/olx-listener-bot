package io.chatbots.olx.storage;

import io.chatbots.olx.storage.entity.Listener;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ListenerJpaRepository extends JpaRepository<Listener, Long> {
    List<Listener> findByChatIdAndActiveTrue(long chatId);

    @Query("SELECT l FROM Listener l WHERE l.active = true")
    List<Listener> findByActiveTrue();

    @Modifying
    @Query("UPDATE Listener l SET l.active = false WHERE l.chatId = :chatId AND l.active = true")
    void deactivateByChatId(@Param("chatId") long chatId);

    @Query(value = """
            SELECT
                COUNT(*)                                             AS allListeners,
                COUNT(*) FILTER (WHERE active)                      AS activeListeners,
                COUNT(DISTINCT user_id)                             AS allUsers,
                COUNT(DISTINCT CASE WHEN active THEN user_id END)   AS activeUsers
            FROM listeners
            """, nativeQuery = true)
    List<Object[]> getAggregateCounts();

    @Query("SELECT l.userLanguageCode, l.active, COUNT(DISTINCT l.userId) FROM Listener l GROUP BY l.userLanguageCode, l.active")
    List<Object[]> getLocaleStats();
}
