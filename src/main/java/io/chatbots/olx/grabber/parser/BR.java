package io.chatbots.olx.grabber.parser;

import io.chatbots.olx.grabber.Offer;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class BR extends BaseParser implements Parser {
    @Override
    public List<Offer> parse(String url) {
        try {

            if (url.contains("?")) {
                url = url + "&";
            } else {
                url = url + "?";
            }
            url = url + "sf=1";

            val body = Jsoup.connect(url).get().body();

            return body.select("ul")
                    .select("svg")
                    .stream()
                    .map(it ->
                            findParentA(it).map(a -> {
                                val link = a.attr("href");
                                val content = "";
                                val name = a.attr("title");
                                return Offer.builder()
                                        .url(cleanUrlFromQueryParams(link))
                                        .content(content)
                                        .name(name)
                                        .build();
                            }).orElse(null)
                    ).filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Error while parsing url:" + url, e);
            return Collections.emptyList();
        }
    }

    private Optional<Element> findParentA(Element it) {
        if (it.is("a")) return Optional.of(it);
        val parent = it.parent();
        if (parent == null) return Optional.empty();
        return findParentA(parent);
    }

}
