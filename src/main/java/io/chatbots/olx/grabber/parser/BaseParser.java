package io.chatbots.olx.grabber.parser;

import com.github.dockerjava.zerodep.shaded.org.apache.hc.core5.net.URIBuilder;
import lombok.SneakyThrows;

import java.util.Collections;

public class BaseParser {

    @SneakyThrows
    String cleanUrlFromQueryParams(String url) {
        return new URIBuilder(url).setParameters(Collections.emptyList()).build().toString();
    }
}
