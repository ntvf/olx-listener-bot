package io.chatbots.olx.config;

import io.chatbots.olx.storage.ListenerJpaRepository;
import io.chatbots.olx.storage.ListenerOfferHashRepository;
import io.chatbots.olx.storage.ListenerStorage;
import io.chatbots.olx.storage.impl.JpaListenerStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {
    @Bean
    public ListenerStorage listenerStorage(ListenerJpaRepository repo,
                                           ListenerOfferHashRepository hashRepository) {
        return new JpaListenerStorage(repo, hashRepository);
    }
}
