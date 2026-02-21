package io.chatbots.olx.grabber.parser;

import lombok.extern.slf4j.Slf4j;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class PlUpdatedAtDateParser {
    private static final Map<String, Integer> POLISH_GENITIVE_MONTHS = new HashMap<>();
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm");

    static {
        POLISH_GENITIVE_MONTHS.put("stycznia", 1);
        POLISH_GENITIVE_MONTHS.put("lutego", 2);
        POLISH_GENITIVE_MONTHS.put("marca", 3);
        POLISH_GENITIVE_MONTHS.put("kwietnia", 4);
        POLISH_GENITIVE_MONTHS.put("maja", 5);
        POLISH_GENITIVE_MONTHS.put("czerwca", 6);
        POLISH_GENITIVE_MONTHS.put("lipca", 7);
        POLISH_GENITIVE_MONTHS.put("sierpnia", 8);
        POLISH_GENITIVE_MONTHS.put("września", 9);
        POLISH_GENITIVE_MONTHS.put("października", 10);
        POLISH_GENITIVE_MONTHS.put("listopada", 11);
        POLISH_GENITIVE_MONTHS.put("grudnia", 12);
    }

    public static Optional<LocalDateTime> parseOlxDate(String input, LocalDate referenceDate) {
        if (input == null) return Optional.empty();
        String text = input.trim();
        if (text.isEmpty()) return Optional.empty();

        String lower = text.toLowerCase();

        // ────────────────────────────────
        //  Case: full date (with or without "Odświeżono dnia")
        // ────────────────────────────────
        Matcher dateMatcher = Pattern.compile(
                "(?:Odświeżono dnia\\s+)?(\\d{1,2})\\s+([a-ząęłńóśźż]+)\\s+(\\d{4})",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
        ).matcher(text);

        if (dateMatcher.find()) {
            try {
                int day = Integer.parseInt(dateMatcher.group(1));
                String monthGen = dateMatcher.group(2).toLowerCase();
                int year = Integer.parseInt(dateMatcher.group(3));

                Integer month = POLISH_GENITIVE_MONTHS.get(monthGen);
                if (month != null) {
                    return Optional.of(LocalDate.of(year, month, day).atStartOfDay());
                }
            } catch (Exception ignored) {
            }
        }
        // ────────────────────────────────
        //  Case: dzisiaj / Dzisiaj + time
        // ────────────────────────────────
        if (lower.contains("dzisiaj")) {
            // Extract time after last "o" (more reliable than greedy .*)
            int lastOIndex = lower.lastIndexOf("o");
            if (lastOIndex >= 0) {
                String afterO = text.substring(lastOIndex + 1).trim();
                // Take first HH:mm-like pattern
                Matcher timeMatcher = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(afterO);
                if (timeMatcher.find()) {
                    try {
                        int hour = Integer.parseInt(timeMatcher.group(1));
                        int minute = Integer.parseInt(timeMatcher.group(2));
                        LocalTime time = LocalTime.of(hour, minute);
                        return Optional.of(LocalDateTime.of(referenceDate, time));
                    } catch (DateTimeException | NumberFormatException ignored) {
                    }
                }
            }

            // Fallback: try to parse last 5 chars if looks like time
            if (text.length() >= 5) {
                String tail = text.substring(text.length() - 5).trim();
                try {
                    LocalTime time = LocalTime.parse(tail, TIME_FMT);
                    return Optional.of(LocalDateTime.of(referenceDate, time));
                } catch (DateTimeParseException ignored) {
                }
            }
        }

        log.warn("Can't parse updated at date time:{}", input);
        return Optional.empty();
    }

    public static LocalDateTime parseOlxDate(String text) {
        return parseOlxDate(text, LocalDate.now()).orElse(null);
    }
}
