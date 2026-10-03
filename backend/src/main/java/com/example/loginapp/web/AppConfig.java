package com.example.loginapp.web;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.loginapp.core.AttendanceImportService;
import com.example.loginapp.core.AttendanceService;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.UserService;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Opens the four database connection pools, applies the migrations at startup
 * and creates the first Super Admin when the platform has none.
 */
@Configuration
public class AppConfig {

    @Bean
    public Db db(@Value("${app.db.url}") String url,
                 @Value("${app.db.owner.user}") String ownerUser,
                 @Value("${app.db.owner.password}") String ownerPassword,
                 @Value("${app.db.role-password}") String rolePassword) {
        Db db = new Db(
                pool(url, ownerUser, ownerPassword, 2),
                pool(url, "app_platform", rolePassword, 5),
                pool(url, "app_auth", rolePassword, 10),
                pool(url, "app_tenant", rolePassword, 10));
        Migrator.migrate(db, rolePassword);
        return db;
    }

    @Bean
    public AuthService authService(Db db) {
        return new AuthService(db);
    }

    @Bean
    public UserService userService(Db db) {
        return new UserService(db);
    }

    @Bean
    public AttendanceService attendanceService(Db db) {
        return new AttendanceService(db);
    }

    @Bean
    public AttendanceSettingsService attendanceSettingsService(Db db) {
        return new AttendanceSettingsService(db);
    }

    @Bean
    public AttendanceImportService attendanceImportService(Db db) {
        return new AttendanceImportService(db);
    }

    @Bean
    public InvitationService invitationService(Db db) {
        return new InvitationService(db);
    }

    @Bean
    public PlatformService platformService(Db db,
                                           @Value("${app.superadmin.email}") String email,
                                           @Value("${app.superadmin.password}") String password) {
        PlatformService platform = new PlatformService(db);
        platform.ensureFirstSuperAdmin(email, password);
        return platform;
    }

    /** A pool that connects lazily, so the application roles can be created by the first migration. */
    private static DataSource pool(String url, String user, String password, int size) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setMaximumPoolSize(size);
        ds.setMinimumIdle(0);
        ds.setInitializationFailTimeout(-1);
        ds.setPoolName("hrms-" + user);
        return ds;
    }
}
