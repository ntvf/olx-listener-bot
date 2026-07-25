package io.chatbots.olx.furniture;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Google AI-Mode photo answer ({@code MODEL: … / DIMS: 77x39x147 cm / SOURCE: …}) into a
 * median {@link FurnitureVariantParser.Variant}, reusing the same width-keying as the text parser so
 * a photo-derived size lands in the same group as a text-derived one. A missing or "unknown" size
 * yields empty, so the offer stays on the bare-model median.
 */
public final class FurnitureAiAnswerParser {

    private static final Pattern DIMS_LINE = Pattern.compile("(?im)^\\s*DIMS:\\s*(.+)$");

    private FurnitureAiAnswerParser() {
    }

    public static Optional<FurnitureVariantParser.Variant> fromAiAnswer(String answer) {
        if (answer == null || answer.isBlank()) return Optional.empty();
        Matcher line = DIMS_LINE.matcher(answer);
        String dims = line.find() ? line.group(1) : answer;
        if (dims.toLowerCase().contains("unknown")) return Optional.empty();

        FurnitureVariantParser.Variant v = FurnitureVariantParser.parse(dims, null);
        return v.isPresent() ? Optional.of(v) : Optional.empty();
    }
}
