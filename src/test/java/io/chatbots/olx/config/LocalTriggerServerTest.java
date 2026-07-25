package io.chatbots.olx.config;

import io.chatbots.olx.channel.ChannelFeedPoller;
import io.chatbots.olx.channel.ChannelPublisher;
import io.chatbots.olx.furniture.FurnitureFeedPoller;
import io.chatbots.olx.furniture.FurniturePhotoEnricher;
import io.chatbots.olx.furniture.FurniturePublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class LocalTriggerServerTest {

    private final FurnitureFeedPoller furniturePoller = mock(FurnitureFeedPoller.class);
    private final FurniturePublisher furniturePublisher = mock(FurniturePublisher.class);
    private final FurniturePhotoEnricher photoEnricher = mock(FurniturePhotoEnricher.class);
    private final ChannelFeedPoller channelPoller = mock(ChannelFeedPoller.class);
    private final ChannelPublisher channelPublisher = mock(ChannelPublisher.class);

    private LocalTriggerServer server;

    private LocalTriggerServer serverOn(int port) {
        return new LocalTriggerServer(port, furniturePoller, furniturePublisher, photoEnricher,
                channelPoller, channelPublisher);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private HttpResponse<String> hit(int port, String action) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/trigger/" + action)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void knownTriggerFiresTheScheduler() throws Exception {
        int port = freePort();
        server = serverOn(port);
        server.start();

        HttpResponse<String> res = hit(port, "ikea-enrich");

        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("triggered: ikea-enrich"));
        verify(photoEnricher).tick();
    }

    @Test
    void unknownTriggerIs404AndTouchesNothing() throws Exception {
        int port = freePort();
        server = serverOn(port);
        server.start();

        HttpResponse<String> res = hit(port, "bogus");

        assertEquals(404, res.statusCode());
        assertTrue(res.body().contains("unknown trigger"));
        verifyNoInteractions(furniturePoller, furniturePublisher, photoEnricher, channelPoller, channelPublisher);
    }

    @Test
    void disabledPortIsANoOp() throws Exception {
        server = serverOn(0);
        server.start(); // must not bind, throw, or wire anything
        server.stop();  // must be safe with no server

        verifyNoInteractions(furniturePoller, furniturePublisher, photoEnricher, channelPoller, channelPublisher);
    }
}
