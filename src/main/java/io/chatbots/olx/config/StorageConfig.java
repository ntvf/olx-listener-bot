package io.chatbots.olx.config;

import com.mongodb.MongoClient;
import io.chatbots.olx.storage.ListenerStorage;
import io.chatbots.olx.storage.impl.MongoListenerStorage;
import org.apache.commons.lang3.StringUtils;
import org.jongo.Jongo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Created by tymur on 11/7/18.
 */
@Configuration
public class StorageConfig {

    @Bean
    public Jongo jongo(@Value("${spring.data.mongodb.host:}") String host,
                       @Value("${spring.data.mongodb.port:}") Integer port) {
        MongoClient client;
        if (StringUtils.isNotBlank(host) && port != null) {
            client = new MongoClient(host, port);
        } else {
            client = new MongoClient();
        }

        return new Jongo(client.getDB("olx_listener"));
    }

    @Bean
    public ListenerStorage mongoListenerStorage(Jongo jongo) {
        return new MongoListenerStorage(jongo);
    }

}
