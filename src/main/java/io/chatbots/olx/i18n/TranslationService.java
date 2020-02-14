package io.chatbots.olx.i18n;

import org.springframework.cache.annotation.Cacheable;

import java.util.Locale;

public interface TranslationService {

    @Cacheable("translations")
    String translate(String key, Locale locale);
}
