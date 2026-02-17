package io.chatbots.olx.container;

import org.testcontainers.containers.GenericContainer;

public abstract class TestMongoContainer {
    private static final int MONGO_PORT = 27017;

    private static final GenericContainer mongo = new GenericContainer("mongo:latest")
            .withExposedPorts(MONGO_PORT);

    static {
        mongo.start();
        System.setProperty("spring.data.mongodb.host", mongo.getContainerIpAddress());
        System.setProperty("spring.data.mongodb.port", String.valueOf(mongo.getFirstMappedPort()));
    }
}
