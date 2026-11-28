package dev.starbridge.text;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Player-facing text helpers. Messages travel as translation keys so each client renders its own
 * language; machine interfaces (CC:Tweaked, the agent bridge) keep the English fallback.
 */
public final class Lang {
    private Lang() {}

    /** A rejected action whose message is a translation key with an English fallback. */
    public static final class LocalizedException extends IllegalStateException {
        private final String key;
        private final Object[] args;

        public LocalizedException(String key, String english, Object... args) {
            super(english);
            this.key = key;
            this.args = args.clone();
        }

        public String key() { return key; }
        public Object[] args() { return args.clone(); }
        public Component component() { return Component.translatableWithFallback(key, getMessage(), args); }
    }

    /** Thrown for invalid arguments, so existing IllegalArgumentException handling still applies. */
    public static final class LocalizedArgumentException extends IllegalArgumentException {
        private final String key;

        public LocalizedArgumentException(String key, String english) {
            super(english);
            this.key = key;
        }

        public String key() { return key; }
    }

    public static LocalizedException error(String key, String english, Object... args) {
        return new LocalizedException("starbridge.error." + key, english, args);
    }

    public static LocalizedArgumentException invalid(String key, String english) {
        return new LocalizedArgumentException("starbridge.error." + key, english);
    }

    public static MutableComponent msg(String key, Object... args) {
        return Component.translatable("starbridge.message." + key, args);
    }

    /** The translation key of a failure, or its raw message when it has none. */
    public static String keyOf(Throwable failure) {
        if (failure instanceof LocalizedException localized) return localized.key();
        if (failure instanceof LocalizedArgumentException localized) return localized.key();
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }

    public static Component of(Throwable failure) {
        if (failure instanceof LocalizedException localized) return localized.component();
        if (failure instanceof LocalizedArgumentException localized)
            return Component.translatableWithFallback(localized.key(), localized.getMessage());
        return Component.literal(failure.getMessage() == null ? "Control request failed" : failure.getMessage());
    }

    /** Renders a stored key; text that is not a known key is shown unchanged. */
    public static Component keyOrText(String keyOrText) {
        return keyOrText.startsWith("starbridge.") || keyOrText.startsWith("message.starbridge.")
                ? Component.translatable(keyOrText) : Component.literal(keyOrText);
    }
}
