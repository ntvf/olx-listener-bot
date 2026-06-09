package io.chatbots.olx.storage;

import io.chatbots.olx.OlxTelegramBot;
import io.chatbots.olx.storage.impl.JpaListenerStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@SpringBootTest
@Testcontainers
class PostgresListenerStorageIntegrationTest extends ListenerStorageContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    @MockitoBean
    OlxTelegramBot olxTelegramBot;
    @MockitoBean
    TelegramClient telegramClient;
    @Autowired
    ListenerJpaRepository repo;
    @Autowired
    ListenerOfferHashRepository hashRepository;

    @DynamicPropertySource
    static void configureDs(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.xml");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("bot.token", () -> "test-token");
        r.add("bot.name", () -> "test-bot");
    }

    @BeforeEach
    void cleanDb() {
        repo.deleteAll();
    }

    @Override
    protected ListenerStorage newStorage() {
        return new JpaListenerStorage(repo, hashRepository);
    }
}
