package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Migrations are forward only: once a file is merged it may never change, because databases that already
 * ran it would silently differ from new ones. Flyway refuses to start against such a database; this test
 * stops the change earlier, in CI, for every file at once.
 *
 * <p>Each file's SHA-256 is recorded in src/test/resources/migration-checksums.txt. A new migration
 * needs its line added there (the failure message gives it); a changed one needs a new migration instead.
 */
class MigratorFilesTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration");
    private static final Path MANIFEST = Path.of("src/test/resources/migration-checksums.txt");

    @Test
    void appliedMigrationsNeverChangeAndNewOnesAreRecorded() throws IOException {
        Map<String, String> recorded = new LinkedHashMap<>();
        for (String line : Files.readAllLines(MANIFEST)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            recorded.put(parts[1], parts[0]);
        }

        List<String> problems = new ArrayList<>();
        Map<String, String> onDisk = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path f : files.sorted().toList()) {
                onDisk.put(f.getFileName().toString(), sha256(f));
            }
        }
        recorded.forEach((name, hash) -> {
            if (!onDisk.containsKey(name)) {
                problems.add(name + " was deleted; applied migrations must stay");
            } else if (!onDisk.get(name).equals(hash)) {
                problems.add(name + " was changed after it was merged; write a new migration instead");
            }
        });
        onDisk.forEach((name, hash) -> {
            if (!recorded.containsKey(name)) {
                problems.add("New migration " + name + ": add this line to " + MANIFEST + ":\n    " + hash + "  " + name);
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void filesAreNamedForFlywayWithVersionsOneToN() throws IOException {
        List<Integer> versions = new ArrayList<>();
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                assertTrue(name.matches("V\\d+__[a-z0-9_]+\\.sql"), name + " is not named V<n>__<words>.sql");
                versions.add(Integer.parseInt(name.substring(1, name.indexOf("__"))));
            }
        }
        versions.sort(null);
        List<Integer> expected = new ArrayList<>();
        for (int v = 1; v <= versions.size(); v++) {
            expected.add(v);
        }
        assertEquals(expected, versions, "migration versions must run 1, 2, 3, ... with no gap or duplicate");
    }

    private static String sha256(Path f) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
