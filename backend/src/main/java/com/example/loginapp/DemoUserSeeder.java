package com.example.loginapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** Adds the demo account to the users table at startup if it is missing. */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private final UserRepository users;
    private final BCryptPasswordEncoder encoder;
    private final String demoUsername;
    private final String demoPassword;

    public DemoUserSeeder(UserRepository users,
                          BCryptPasswordEncoder encoder,
                          @Value("${app.demo.username}") String demoUsername,
                          @Value("${app.demo.password}") String demoPassword) {
        this.users = users;
        this.encoder = encoder;
        this.demoUsername = demoUsername;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.findPasswordHash(demoUsername).isEmpty()) {
            users.create(demoUsername, encoder.encode(demoPassword));
        }
    }
}
