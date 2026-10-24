package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.Migrator;

/**
 * The migrator runs an explicit list of files, so a new migration left out of it is silently never applied.
 * Every file in db/migration must be listed, in version order.
 */
class MigratorFilesTest {

    @Test
    void everyMigrationFileIsListedInOrder() throws IOException {
        List<String> onDisk;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            onDisk = files.map(f -> f.getFileName().toString()).filter(n -> n.matches("V\\d+__.+\\.sql"))
                    .sorted(Comparator.comparingInt(n -> Integer.parseInt(n.substring(1, n.indexOf("__"))))).toList();
        }
        assertEquals(onDisk, Migrator.files());
    }
}
