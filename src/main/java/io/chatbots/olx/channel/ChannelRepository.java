package io.chatbots.olx.channel;

import io.chatbots.olx.channel.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

    Optional<Channel> findByUsernameIgnoreCase(String username);

    Optional<Channel> findByTitleIgnoreCase(String title);
}
