package io.chatbots.olx.grabber;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stable dedup key for an OLX offer.
 *
 * <p>OLX, Otodom and Otomoto listing URLs embed an editable, human-readable slug before the immutable
 * ad id: {@code .../kawalerka-na-zoliborzu-CID3-ID1bA1FD.html} (OLX), {@code .../mieszkanie-na-woli-ID4Cm5b}
 * (Otodom, no {@code .html} suffix) and {@code .../seat-cordoba-OLX_ID6IaTcN.html} (Otomoto). When a
 * seller edits the title the slug changes but the {@code ID<token>} stays, so hashing the whole URL makes
 * an edited listing look brand-new and re-posts it. Keying on the ad id alone avoids that.
 *
 * <p>URLs without a recognised {@code -ID}/{@code _ID} segment fall back to the full URL, preserving
 * previous behaviour.
 */
public final class OfferKey {

    // -ID for OLX/Otodom, _ID for Otomoto's OLX_ID<token>; slugs use hyphens so this never mis-hits earlier.
    private static final Pattern OLX_AD_ID = Pattern.compile("[-_]ID([0-9A-Za-z]+)");

    private OfferKey() {
    }

    public static String of(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = OLX_AD_ID.matcher(url);
        return m.find() ? m.group(1) : url;
    }
}
