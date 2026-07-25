package io.chatbots.olx.score;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CaptchaTunnelServiceTest {

    @Test
    void staticUrlTakesPrecedence() throws Exception {
        CaptchaTunnelService service = service("http://192.168.1.10:6080/vnc.html", "does-not-matter");
        assertEquals("http://192.168.1.10:6080/vnc.html", service.openAccessUrl());
    }

    @Test
    void extractsTunnelUrlFromProcessOutput() throws Exception {
        Path script = fakeCloudflared(
                "echo '2026-07-12 INF Thank you for trying Cloudflare Tunnel.' >&2\n"
                        + "echo '2026-07-12 INF |  https://random-words-1234.trycloudflare.com  |' >&2\n"
                        + "sleep 30\n");
        CaptchaTunnelService service = service("", script.toString());
        try {
            assertEquals("https://random-words-1234.trycloudflare.com/vnc.html?autoconnect=1&resize=scale",
                    service.openAccessUrl());
        } finally {
            service.close();
        }
    }

    @Test
    void reusesLiveTunnelInsteadOfRespawning() throws Exception {
        Path launches = Files.createTempFile("cf-launches", ".log");
        Path script = fakeCloudflared(
                "echo x >> " + launches + "\n"
                        + "echo '2026-07-12 INF |  https://reused-1234.trycloudflare.com  |' >&2\n"
                        + "sleep 30\n");
        CaptchaTunnelService service = service("", script.toString());
        try {
            String first = service.openAccessUrl();
            String second = service.openAccessUrl(); // same live process — must not respawn
            assertEquals(first, second);
            assertEquals(1, Files.readAllLines(launches).size(), "tunnel respawned instead of being reused");
        } finally {
            service.close();
        }
    }

    @Test
    void releaseKeepsTunnelAliveWithinIdleWindow() throws Exception {
        Path launches = Files.createTempFile("cf-launches", ".log");
        Path script = fakeCloudflared(
                "echo x >> " + launches + "\n"
                        + "echo '2026-07-12 INF |  https://idle-1234.trycloudflare.com  |' >&2\n"
                        + "sleep 30\n");
        CaptchaTunnelService service = service("", script.toString());
        set(service, "tunnelIdleMinutes", 15); // release schedules a close far in the future, not now
        try {
            String first = service.openAccessUrl();
            service.release();                       // attempt done, but tunnel should linger
            String second = service.openAccessUrl(); // still the same live tunnel
            assertEquals(first, second);
            assertEquals(1, Files.readAllLines(launches).size(), "tunnel closed on release instead of lingering");
        } finally {
            service.close();
        }
    }

    @Test
    void returnsNullWhenProcessPrintsNoUrl() throws Exception {
        Path script = fakeCloudflared("echo 'failed to connect' >&2\nexit 1\n");
        CaptchaTunnelService service = service("", script.toString());
        assertNull(service.openAccessUrl());
    }

    @Test
    void returnsNullWhenCommandMissing() throws Exception {
        CaptchaTunnelService service = service("", "/nonexistent/cloudflared tunnel");
        assertNull(service.openAccessUrl());
    }

    private Path fakeCloudflared(String body) throws Exception {
        Path script = Files.createTempFile("fake-cloudflared", ".sh");
        Files.writeString(script, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        script.toFile().deleteOnExit();
        return script;
    }

    private CaptchaTunnelService service(String staticUrl, String command) throws Exception {
        CaptchaTunnelService service = new CaptchaTunnelService();
        set(service, "staticCaptchaUrl", staticUrl);
        set(service, "tunnelCommand", command);
        set(service, "tunnelReadyWaitSeconds", 0); // don't probe fake URLs over the network
        return service;
    }

    private void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
