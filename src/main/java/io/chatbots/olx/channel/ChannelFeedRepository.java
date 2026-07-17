package io.chatbots.olx.channel;

import io.chatbots.olx.channel.entity.ChannelFeed;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChannelFeedRepository extends JpaRepository<ChannelFeed, Long> {

    List<ChannelFeed> findByActiveTrue();

    List<ChannelFeed> findByChannelChatId(long channelChatId);
}
