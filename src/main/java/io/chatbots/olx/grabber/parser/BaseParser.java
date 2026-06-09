package io.chatbots.olx.grabber.parser;

import lombok.SneakyThrows;

import java.net.URI;

public class BaseParser {

    @SneakyThrows
    String cleanUrlFromQueryParams(String url) {
        URI uri = new URI(url);
        return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString();
    }
}
