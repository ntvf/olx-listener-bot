package io.chatbots.olx.config;

import com.mongodb.MongoClient;
import io.chatbots.olx.storage.ListenerStorage;
import io.chatbots.olx.storage.impl.MongoListenerStorage;
import org.jongo.Jongo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Created by tymur on 11/7/18.
 */
@Configuration
public class StorageConfig {

    @Bean
    public Jongo jongo() {
        return new Jongo(new MongoClient().getDB("olx_listener"));
    }

    @Bean
    public ListenerStorage mongoListenerStorage(Jongo jongo) {
        return new MongoListenerStorage(jongo);
    }

}
