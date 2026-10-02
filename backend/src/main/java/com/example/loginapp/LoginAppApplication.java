package com.example.loginapp;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LoginAppApplication {

    public static void main(String[] args) {
        // The server works in UTC. This also keeps the database connection from
        // sending a local time-zone name that the PostgreSQL server may not know.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(LoginAppApplication.class, args);
    }
}
