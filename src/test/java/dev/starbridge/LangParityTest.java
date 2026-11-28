package dev.starbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Every player-facing key must exist in both shipped languages, exactly once. */
class LangParityTest {
    private static final String ROOT = "/assets/starbridge/lang/";

    private static String read(String file) throws IOException {
        try (InputStream in = Objects.requireNonNull(LangParityTest.class.getResourceAsStream(ROOT + file), file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> keys(String file) throws IOException {
        JsonObject json = JsonParser.parseString(read(file)).getAsJsonObject();
        return new TreeSet<>(json.keySet());
    }

    @Test
    void englishAndChineseHaveTheSameKeys() throws IOException {
        Set<String> english = keys("en_us.json"), chinese = keys("zh_cn.json");
        var onlyEnglish = new TreeSet<>(english);
        onlyEnglish.removeAll(chinese);
        var onlyChinese = new TreeSet<>(chinese);
        onlyChinese.removeAll(english);
        assertTrue(onlyEnglish.isEmpty(), "Missing from zh_cn: " + onlyEnglish);
        assertTrue(onlyChinese.isEmpty(), "Missing from en_us: " + onlyChinese);
    }

    @Test
    void noKeyIsDeclaredTwice() throws IOException {
        Pattern key = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:", Pattern.MULTILINE);
        for (String file : new String[]{"en_us.json", "zh_cn.json"}) {
            Set<String> seen = new HashSet<>();
            Matcher matcher = key.matcher(read(file));
            while (matcher.find()) assertTrue(seen.add(matcher.group(1)), file + " repeats " + matcher.group(1));
        }
    }
}
