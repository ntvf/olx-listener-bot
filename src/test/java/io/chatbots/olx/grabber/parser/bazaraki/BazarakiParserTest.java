package io.chatbots.olx.grabber.parser.bazaraki;

import io.chatbots.olx.grabber.Offer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BazarakiParserTest {

    private final BazarakiParser parser = new BazarakiParser(null);

    @Test
    void extractsListingsFromCurrentMarkup() throws Exception {
        List<Offer> offers = parser.parseHtml(fixture(), "https://www.bazaraki.com/telephones/mobile-phones/");

        assertThat(offers).isNotEmpty();
        assertThat(offers).allSatisfy(offer -> {
            assertThat(offer.getUrl()).startsWith("https://www.bazaraki.com/adv/");
            assertThat(offer.getName()).isNotBlank();
            assertThat(offer.getContent()).contains(offer.getName());
        });
    }

    @Test
    void skipsPaidVipAdsPinnedToTheTop() throws Exception {
        List<Offer> offers = parser.parseHtml(fixture(), "https://www.bazaraki.com/telephones/mobile-phones/");

        assertThat(offers).noneSatisfy(offer ->
                assertThat(offer.getUrl()).contains("6639059")); // a data-t-vip card in the fixture
    }

    private String fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/bazaraki/mobile-phones.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
