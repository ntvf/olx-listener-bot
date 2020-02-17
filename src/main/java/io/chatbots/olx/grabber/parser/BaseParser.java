package io.chatbots.olx.grabber.parser;

import lombok.SneakyThrows;
import org.apache.http.client.utils.URIBuilder;

import java.util.Collections;

public class BaseParser {

    @SneakyThrows
    String cleanUrlFromQueryParams(String url) {
        return new URIBuilder(url).setParameters(Collections.emptyList()).build().toString();
    }
}
