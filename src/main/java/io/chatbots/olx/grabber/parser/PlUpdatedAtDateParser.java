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
    private static final Map<String, Integer> MONTH_NAMES = new HashMap<>();
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm");

    static {
        // Polish genitive
        MONTH_NAMES.put("stycznia", 1);
        MONTH_NAMES.put("lutego", 2);
        MONTH_NAMES.put("marca", 3);
        MONTH_NAMES.put("kwietnia", 4);
        MONTH_NAMES.put("maja", 5);
        MONTH_NAMES.put("czerwca", 6);
        MONTH_NAMES.put("lipca", 7);
        MONTH_NAMES.put("sierpnia", 8);
        MONTH_NAMES.put("września", 9);
        MONTH_NAMES.put("października", 10);
        MONTH_NAMES.put("listopada", 11);
        MONTH_NAMES.put("grudnia", 12);
        // Portuguese
        MONTH_NAMES.put("janeiro", 1);
        MONTH_NAMES.put("fevereiro", 2);
        MONTH_NAMES.put("março", 3);
        MONTH_NAMES.put("abril", 4);
        MONTH_NAMES.put("maio", 5);
        MONTH_NAMES.put("junho", 6);
        MONTH_NAMES.put("julho", 7);
        MONTH_NAMES.put("agosto", 8);
        MONTH_NAMES.put("setembro", 9);
        MONTH_NAMES.put("outubro", 10);
        MONTH_NAMES.put("novembro", 11);
        MONTH_NAMES.put("dezembro", 12);
        // Bulgarian
        MONTH_NAMES.put("януари", 1);
        MONTH_NAMES.put("февруари", 2);
        MONTH_NAMES.put("март", 3);
        MONTH_NAMES.put("април", 4);
        MONTH_NAMES.put("май", 5);
        MONTH_NAMES.put("юни", 6);
        MONTH_NAMES.put("юли", 7);
        MONTH_NAMES.put("август", 8);
        MONTH_NAMES.put("септември", 9);
        MONTH_NAMES.put("октомври", 10);
        MONTH_NAMES.put("ноември", 11);
        MONTH_NAMES.put("декември", 12);
        // Ukrainian genitive
        MONTH_NAMES.put("січня", 1);
        MONTH_NAMES.put("лютого", 2);
        MONTH_NAMES.put("березня", 3);
        MONTH_NAMES.put("квітня", 4);
        MONTH_NAMES.put("травня", 5);
        MONTH_NAMES.put("червня", 6);
        MONTH_NAMES.put("липня", 7);
        MONTH_NAMES.put("серпня", 8);
        MONTH_NAMES.put("вересня", 9);
        MONTH_NAMES.put("жовтня", 10);
        MONTH_NAMES.put("листопада", 11);
        MONTH_NAMES.put("грудня", 12);
        // Russian genitive
        MONTH_NAMES.put("января", 1);
        MONTH_NAMES.put("февраля", 2);
        MONTH_NAMES.put("марта", 3);
        MONTH_NAMES.put("апреля", 4);
        MONTH_NAMES.put("мая", 5);
        MONTH_NAMES.put("июня", 6);
        MONTH_NAMES.put("июля", 7);
        MONTH_NAMES.put("августа", 8);
        MONTH_NAMES.put("сентября", 9);
        MONTH_NAMES.put("октября", 10);
        MONTH_NAMES.put("ноября", 11);
        MONTH_NAMES.put("декабря", 12);
        // Romanian
        MONTH_NAMES.put("ianuarie", 1);
        MONTH_NAMES.put("februarie", 2);
        MONTH_NAMES.put("martie", 3);
        MONTH_NAMES.put("aprilie", 4);
        MONTH_NAMES.put("mai", 5);
        MONTH_NAMES.put("iunie", 6);
        MONTH_NAMES.put("iulie", 7);
        MONTH_NAMES.put("august", 8);
        MONTH_NAMES.put("septembrie", 9);
        MONTH_NAMES.put("octombrie", 10);
        MONTH_NAMES.put("noiembrie", 11);
        MONTH_NAMES.put("decembrie", 12);
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
                "(?:(?:Odświeżono dnia|Para o topo a|de)\\s+)?(\\d{1,2})\\s+(?:de\\s+)?([a-ząęłńóśźżçãõáéíóúàâêô\\u0400-\\u04FF]+)\\s+(?:de\\s+)?(\\d{4})",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
        ).matcher(text);

        if (dateMatcher.find()) {
            try {
                int day = Integer.parseInt(dateMatcher.group(1));
                String monthName = dateMatcher.group(2).toLowerCase();
                int year = Integer.parseInt(dateMatcher.group(3));

                Integer month = MONTH_NAMES.get(monthName);
                if (month != null) {
                    return Optional.of(LocalDate.of(year, month, day).atStartOfDay());
                }
            } catch (Exception ignored) {
            }
        }
        // ────────────────────────────────
        //  Case: dzisiaj / Dzisiaj + time  (Polish)
        //        Сегодня / Сьогодні        (Russian / Ukrainian today)
        //        Вчера / Вчора             (Russian / Ukrainian yesterday)
        //        Hoje                      (Portuguese today)
        //        Ontem                     (Portuguese yesterday)
        //        azi                       (Romanian today)
        //        ieri                      (Romanian yesterday)
        //        днес                      (Bulgarian today)
        // ────────────────────────────────
        boolean isToday = lower.contains("dzisiaj")
                || lower.contains("сегодня")
                || lower.contains("сьогодні")
                || lower.contains("hoje")
                || lower.contains("azi")
                || lower.contains("днес");
        boolean isYesterday = lower.contains("вчера") || lower.contains("вчора")
                || lower.contains("ontem")
                || lower.contains("ieri");

        if (isToday || isYesterday) {
            LocalDate baseDate = isYesterday ? referenceDate.minusDays(1) : referenceDate;

            // Extract time after last preposition: "o" (Polish), "в" (Russian), "о" (Ukrainian)
            Matcher timeMatcher = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(text);
            if (timeMatcher.find()) {
                try {
                    int hour = Integer.parseInt(timeMatcher.group(1));
                    int minute = Integer.parseInt(timeMatcher.group(2));
                    LocalTime time = LocalTime.of(hour, minute);
                    return Optional.of(LocalDateTime.of(baseDate, time));
                } catch (DateTimeException | NumberFormatException ignored) {
                }
            }

            // Fallback: try to parse last 5 chars if looks like time
            if (text.length() >= 5) {
                String tail = text.substring(text.length() - 5).trim();
                try {
                    LocalTime time = LocalTime.parse(tail, TIME_FMT);
                    return Optional.of(LocalDateTime.of(baseDate, time));
                } catch (DateTimeParseException ignored) {
                }
            }
        }

        log.debug("Can't parse updated at date time:{}", input);
        return Optional.empty();
    }

    public static LocalDateTime parseOlxDate(String text) {
        return parseOlxDate(text, LocalDate.now()).orElse(null);
    }
}
