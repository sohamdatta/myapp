package com.example.loginapp;

import java.util.Arrays;
import java.util.TimeZone;

import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.example.loginapp.core.Migrator;

@SpringBootApplication
public class LoginAppApplication {

    public static void main(String[] args) {
        // The server works in UTC. This also keeps the database connection from
        // sending a local time-zone name that the PostgreSQL server may not know.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        if (Arrays.asList(args).contains("--check-migrations")) {
            System.exit(checkMigrations());
        }
        SpringApplication.run(LoginAppApplication.class, args);
    }

    /**
     * The dry run before a deploy (docs/database.md): which migrations the next start applies, and whether
     * it would refuse. Reads the same settings as the app (APP_DB_URL, APP_DB_OWNER_USER,
     * APP_DB_OWNER_PASSWORD, with application.properties' defaults) and changes nothing.
     */
    private static int checkMigrations() {
        PGSimpleDataSource owner = new PGSimpleDataSource();
        owner.setUrl(env("APP_DB_URL", "jdbc:postgresql://localhost:5432/hrms"));
        owner.setUser(env("APP_DB_OWNER_USER", "postgres"));
        owner.setPassword(env("APP_DB_OWNER_PASSWORD", "postgres"));
        return Migrator.check(owner, System.out);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
