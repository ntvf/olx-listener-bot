package io.chatbots.olx.score;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code RANGE:/COMPS:/NOTE:} lines the score prompt asks AI Mode for.
 * <p>
 * AI Mode is a scraped surface and does not always honour the format, so every method here returns
 * null rather than throwing: the caller falls back to posting the raw answer.
 */
@Slf4j
public class MarketPriceParser {

    /**
     * A number with optional group separators — "1200", "1 200", "1.200,50". Bounded by digits so a
     * greedy match cannot spill into the currency, and free of {@code \s} so it cannot cross a line
     * break and swallow the next field.
     */
    private static final String NUMBER = "\\d[\\d .,\u00a0\u202f]*\\d|\\d";
    private static final Pattern RANGE = Pattern.compile(
            "RANGE:[ \\t]*(" + NUMBER + ")[ \\t]*[-–—]+[ \\t]*(" + NUMBER + ")[ \\t]*([^\\s\\n]*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPS = Pattern.compile("COMPS:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOTE = Pattern.compile("NOTE:\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT = Pattern.compile(NUMBER);

    /** @return the parsed market price, or null if the answer did not follow the format */
    public MarketPrice parse(String answer) {
        if (StringUtils.isBlank(answer)) return null;

        Integer comparables = firstInt(COMPS, answer);
        String note = firstGroup(NOTE, answer);

        Matcher range = RANGE.matcher(answer);
        if (!range.find()) {
            // "COMPS: 0" with no range is a valid, meaningful answer: it found nothing to compare
            if (comparables != null && comparables == 0) {
                return MarketPrice.builder().comparables(0).note(note).build();
            }
            return null;
        }

        Double low = parseAmount(range.group(1));
        Double high = parseAmount(range.group(2));
        if (low == null || high == null || low <= 0 || high < low) {
            log.debug("Unusable RANGE in AI answer: {}", range.group());
            return null;
        }
        return MarketPrice.builder()
                .low(low)
                .high(high)
                .currency(StringUtils.trimToNull(range.group(3)))
                .comparables(comparables == null ? 0 : comparables)
                .note(note)
                .build();
    }

    /** Pulls the first amount out of a scraped price string such as "1 200 zł" or "1200 PLN". */
    public Double parseListingPrice(String price) {
        if (StringUtils.isBlank(price)) return null;
        Matcher matcher = AMOUNT.matcher(price);
        return matcher.find() ? parseAmount(matcher.group()) : null;
    }

    /**
     * Parses a localised amount. Separators are ambiguous across the markets this bot covers
     * ("1.200" is twelve hundred in PL, "1.200" is 1.2 in en-US), so a group of exactly three
     * digits after the last separator is read as a thousands group, not a fraction.
     */
    private Double parseAmount(String raw) {
        String digits = raw.replaceAll("[\\s\u00a0\u202f]", "");
        if (digits.isEmpty()) return null;
        int lastSeparator = Math.max(digits.lastIndexOf('.'), digits.lastIndexOf(','));
        String normalised;
        if (lastSeparator < 0) {
            normalised = digits;
        } else {
            String tail = digits.substring(lastSeparator + 1);
            String head = digits.substring(0, lastSeparator).replaceAll("[.,]", "");
            normalised = tail.length() == 3 ? head + tail : head + "." + tail;
        }
        try {
            return Double.parseDouble(normalised);
        } catch (NumberFormatException e) {
            log.debug("Could not parse amount '{}'", raw);
            return null;
        }
    }

    private Integer firstInt(Pattern pattern, String text) {
        String value = firstGroup(pattern, text);
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? StringUtils.trimToNull(matcher.group(1)) : null;
    }
}
