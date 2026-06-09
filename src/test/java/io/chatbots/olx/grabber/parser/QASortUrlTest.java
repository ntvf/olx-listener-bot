package io.chatbots.olx.grabber.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QASortUrlTest {

    private static final String SORT_PARAM = "search%5Border%5D=created_at:desc";

    private static long countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    @Test
    void urlWithNoQueryString_appendsSortParam() {
        String result = QA.getSortedByLastCreatedUrl("https://www.olx.pl/oferty/q-iphone");

        assertThat(result).isEqualTo("https://www.olx.pl/oferty/q-iphone?" + SORT_PARAM);
    }

    @Test
    void urlWithOtherParams_prependsSortParam() {
        String result = QA.getSortedByLastCreatedUrl(
                "https://www.olx.pl/oferty/q-iphone?price_from=100&price_to=5000");

        assertThat(result).startsWith("https://www.olx.pl/oferty/q-iphone?" + SORT_PARAM);
        assertThat(result).contains("price_from=100");
        assertThat(result).contains("price_to=5000");
    }

    @Test
    void urlWithExistingSortParam_replacesIt() {
        String input = "https://www.olx.pl/oferty/q-iphone?search%5Border%5D=filter_float_price:asc&other=1";

        String result = QA.getSortedByLastCreatedUrl(input);

        long sortParamCount = countOccurrences(result, "search%5Border%5D=");
        assertThat(sortParamCount).as("sort param should appear exactly once").isEqualTo(1);
        assertThat(result).contains(SORT_PARAM);
        assertThat(result).doesNotContain("filter_float_price:asc");
    }

    @Test
    void urlWithOnlySortParam_replacesIt() {
        String input = "https://www.olx.pl/oferty/q-iphone?search%5Border%5D=filter_float_price:asc";

        String result = QA.getSortedByLastCreatedUrl(input);

        assertThat(result).contains(SORT_PARAM);
        assertThat(result).doesNotContain("filter_float_price:asc");
    }

    @Test
    void preservesOtherQueryParams() {
        String input = "https://www.olx.pl/oferty/q-iphone?category=electronics&price_max=2000";

        String result = QA.getSortedByLastCreatedUrl(input);

        assertThat(result).contains("category=electronics");
        assertThat(result).contains("price_max=2000");
    }
}
