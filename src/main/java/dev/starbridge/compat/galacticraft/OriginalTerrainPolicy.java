package dev.starbridge.compat.galacticraft;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Records which previously generated Retrofit dimensions need their original zero terrain seed. */
final class OriginalTerrainPolicy {
    static final String FILE = "starbridge-original-terrain-v1.txt";
    private static final String HEADER = "host-seed-v1";
    private static final String PREFIX = "legacy_dim_";
    private OriginalTerrainPolicy() {}

    static Set<Integer> load(Path worldRoot) throws IOException {
        Path file = worldRoot.resolve("data").resolve(FILE);
        if (Files.exists(file)) return read(file);
        Set<Integer> existing = new TreeSet<>();
        Path domains = worldRoot.resolve("dimensions").resolve("retrofit");
        if (Files.isDirectory(domains)) {
            try (var directories = Files.newDirectoryStream(domains, PREFIX + "*")) {
                for (Path directory : directories) {
                    Integer dimension = id(directory.getFileName().toString().substring(PREFIX.length()));
                    if (dimension != null && hasChunks(directory.resolve("region"))) existing.add(dimension);
                }
            }
        }
        Files.createDirectories(file.getParent());
        Path scratch = Files.createTempFile(file.getParent(), "starbridge-terrain-", ".tmp");
        try {
            List<String> lines = new ArrayList<>();
            lines.add(HEADER);
            existing.forEach(dimension -> lines.add(Integer.toString(dimension)));
            Files.write(scratch, lines, StandardCharsets.US_ASCII);
            try { Files.move(scratch, file, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(scratch, file); }
        } finally {
            Files.deleteIfExists(scratch);
        }
        return Collections.unmodifiableSet(existing);
    }

    private static Set<Integer> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
        if (lines.isEmpty() || !HEADER.equals(lines.get(0))) throw new IOException("Unknown terrain settings in " + file);
        Set<Integer> existing = new TreeSet<>();
        for (int line = 1; line < lines.size(); ++line) {
            String value = lines.get(line).trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            Integer dimension = id(value);
            if (dimension == null) throw new IOException("Invalid legacy dimension at " + file + ":" + (line + 1));
            existing.add(dimension);
        }
        return Collections.unmodifiableSet(existing);
    }

    private static Integer id(String value) {
        try { return Integer.valueOf(value); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static boolean hasChunks(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return false;
        try (var regions = Files.newDirectoryStream(folder, "r.*.*.mca")) {
            for (Path region : regions) {
                if (Files.size(region) == 0) continue;
                try (var data = new DataInputStream(Files.newInputStream(region))) {
                    for (int slot = 0; slot < 1024; ++slot) {
                        // A region's first sector maps each chunk to a sector offset and length.
                        if (data.readInt() != 0) return true;
                    }
                }
            }
        }
        return false;
    }
}
