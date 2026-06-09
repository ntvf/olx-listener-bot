package io.chatbots.olx.grabber.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaseParserTest {

    // Concrete anonymous subclass to expose the package-private method.
    private final BaseParser parser = new BaseParser() {
    };

    @Test
    void stripsQueryString() {
        String result = parser.cleanUrlFromQueryParams(
                "https://www.olx.pl/oferty/q-iphone/?page=2&sort=price");

        assertThat(result).isEqualTo("https://www.olx.pl/oferty/q-iphone/");
    }

    @Test
    void preservesUrlWithNoQueryString() {
        String result = parser.cleanUrlFromQueryParams(
                "https://www.olx.pl/d/item/iphone-14.html");

        assertThat(result).isEqualTo("https://www.olx.pl/d/item/iphone-14.html");
    }

    @Test
    void stripsPromotedQueryParam() {
        String result = parser.cleanUrlFromQueryParams(
                "https://www.olx.pl/d/item/iphone.html?promoted=1&reason=featured");

        assertThat(result).isEqualTo("https://www.olx.pl/d/item/iphone.html");
    }

    @Test
    void handlesEncodedQueryParams() {
        String result = parser.cleanUrlFromQueryParams(
                "https://www.olx.pl/oferty/q-iphone/?search%5Border%5D=created_at:desc");

        assertThat(result).isEqualTo("https://www.olx.pl/oferty/q-iphone/");
    }

    @Test
    void preservesTrailingSlash() {
        String result = parser.cleanUrlFromQueryParams(
                "https://www.olx.ua/list/q-iphone/?filter=1");

        assertThat(result).isEqualTo("https://www.olx.ua/list/q-iphone/");
    }

    @Test
    void preservesPathWithoutTrailingSlash() {
        String result = parser.cleanUrlFromQueryParams(
                "https://www.olx.pl/oferty/q-iphone?page=1");

        assertThat(result).isEqualTo("https://www.olx.pl/oferty/q-iphone");
    }
}
