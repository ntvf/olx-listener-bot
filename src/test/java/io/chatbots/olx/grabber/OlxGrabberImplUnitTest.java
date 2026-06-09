package io.chatbots.olx.grabber;

import io.chatbots.olx.grabber.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OlxGrabberImplUnitTest {

    private static Offer offer(String name, String url) {
        return Offer.builder().name(name).url(url).build();
    }

    @Test
    void routesUrlToMatchingParser() {
        Parser mockParser = mock(Parser.class);
        when(mockParser.parse(any())).thenReturn(List.of(offer("iPhone", "https://www.olx.pl/d/item.html")));

        OlxGrabberImpl grabber = new OlxGrabberImpl(Map.of("olx.pl", mockParser));

        List<Offer> result = grabber.getOffers("https://www.olx.pl/oferty/q-iphone/");

        assertThat(result).hasSize(1);
        verify(mockParser).parse("https://www.olx.pl/oferty/q-iphone/");
    }

    @Test
    void passesOriginalUrlToParser() {
        Parser mockParser = mock(Parser.class);
        when(mockParser.parse(any())).thenReturn(Collections.emptyList());

        OlxGrabberImpl grabber = new OlxGrabberImpl(Map.of("olx.ua", mockParser));
        String url = "https://www.olx.ua/list/q-iphone/?filter=price:1000";

        grabber.getOffers(url);

        verify(mockParser).parse(url);
    }

    @Test
    void matchesParserBySubdomainHost() {
        Parser mockParser = mock(Parser.class);
        when(mockParser.parse(any())).thenReturn(Collections.emptyList());

        // key "olx.ua" must match host "www.olx.ua"
        OlxGrabberImpl grabber = new OlxGrabberImpl(Map.of("olx.ua", mockParser));

        grabber.getOffers("https://www.olx.ua/list/q-phone/");

        verify(mockParser).parse(any());
    }

    @Test
    void returnsEmptyListForUnknownHost() {
        OlxGrabberImpl grabber = new OlxGrabberImpl(Collections.emptyMap());

        List<Offer> result = grabber.getOffers("https://www.unknown-site.com/search?q=test");

        assertThat(result).isEmpty();
    }

    @Test
    void unknownHostDoesNotCallAnyParser() {
        Parser mockParser = mock(Parser.class);
        OlxGrabberImpl grabber = new OlxGrabberImpl(Map.of("olx.pl", mockParser));

        grabber.getOffers("https://www.ebay.com/search?q=phone");

        verify(mockParser, never()).parse(any());
    }

    @Test
    void multipleParserKeys_onlyMatchingOneIsCalled() {
        Parser olxParser = mock(Parser.class);
        Parser bazarakiParser = mock(Parser.class);
        when(olxParser.parse(any())).thenReturn(Collections.emptyList());

        OlxGrabberImpl grabber = new OlxGrabberImpl(Map.of(
                "olx.pl", olxParser,
                "bazaraki.com", bazarakiParser
        ));

        grabber.getOffers("https://www.olx.pl/oferty/q-test/");

        verify(olxParser).parse(any());
        verify(bazarakiParser, never()).parse(any());
    }
}
