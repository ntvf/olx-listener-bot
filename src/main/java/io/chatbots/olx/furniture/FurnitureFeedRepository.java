package io.chatbots.olx.furniture;

import io.chatbots.olx.furniture.entity.FurnitureFeed;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FurnitureFeedRepository extends JpaRepository<FurnitureFeed, Long> {

    List<FurnitureFeed> findByActiveTrue();

    List<FurnitureFeed> findByChannelChatId(long channelChatId);
}
