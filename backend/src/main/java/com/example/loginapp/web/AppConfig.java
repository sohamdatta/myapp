package com.example.loginapp.web;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.loginapp.core.AttendanceImportService;
import com.example.loginapp.core.AttendanceService;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.ChecklistService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.DocumentService;
import com.example.loginapp.core.EmployeeImport;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.JoinerService;
import com.example.loginapp.core.LeaveAdminService;
import com.example.loginapp.core.ExitInterview;
import com.example.loginapp.core.ExitLetters;
import com.example.loginapp.core.ExitService;
import com.example.loginapp.core.ExitSettlement;
import com.example.loginapp.core.ExitTasks;
import com.example.loginapp.core.LetterService;
import com.example.loginapp.core.LeaveService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.Notifications;
import com.example.loginapp.core.NumberSeries;
import com.example.loginapp.core.OnboardingConfigService;
import com.example.loginapp.core.PayrollConfig;
import com.example.loginapp.core.PayrollPay;
import com.example.loginapp.core.PayrollRuns;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.PolicyService;
import com.example.loginapp.core.UserService;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Opens the four database connection pools, applies the migrations at startup
 * and creates the first Super Admin when the platform has none.
 */
@Configuration
public class AppConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfig.class);

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
    public LeaveService leaveService(Db db) {
        return new LeaveService(db);
    }

    @Bean
    public LeaveAdminService leaveAdminService(Db db) {
        return new LeaveAdminService(db);
    }

    /** The key that encrypts sensitive values. See application.properties. */
    @Bean
    public Crypto crypto(@Value("${app.data-key}") String key,
                         @Value("${app.data-key.id}") int keyId,
                         @Value("${app.data-key.previous}") String previous,
                         @Value("${app.data-key.required}") boolean required) {
        if (key == null || key.isBlank()) {
            if (required) {
                throw new IllegalStateException("APP_DATA_KEY is not set, and APP_REQUIRE_DATA_KEY is true. "
                        + "Set it to 32 random bytes, base64 (openssl rand -base64 32)");
            }
            LOG.warn("APP_DATA_KEY is not set: sensitive values are encrypted with the fixed DEVELOPMENT key. "
                    + "Set APP_DATA_KEY and APP_REQUIRE_DATA_KEY=true before any real use");
            return Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        }
        Map<Integer, String> keys = new LinkedHashMap<>();
        keys.put(keyId, key);
        if (previous != null && !previous.isBlank()) {
            String[] parts = previous.trim().split(":", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("APP_DATA_KEY_PREVIOUS must be id:key, like 1:base64...");
            }
            keys.put(Integer.parseInt(parts[0]), parts[1]);
        }
        return new Crypto(keyId, keys);
    }

    @Bean
    public EmployeeService employeeService(Db db, Crypto crypto) {
        return new EmployeeService(db, crypto);
    }

    @Bean
    public DocumentService documentService(Db db, Crypto crypto) {
        return new DocumentService(db, crypto);
    }

    @Bean
    public ChecklistService checklistService(Db db, DocumentService documents) {
        return new ChecklistService(db, documents);
    }

    @Bean
    public EmployeeImport employeeImport(Db db, Crypto crypto, EmployeeService employees) {
        return new EmployeeImport(db, crypto, employees);
    }

    @Bean
    public ExitService exitService(Db db, Crypto crypto) {
        return new ExitService(db, crypto);
    }

    @Bean
    public ExitSettlement exitSettlement(Db db, Crypto crypto) {
        return new ExitSettlement(db, crypto);
    }

    @Bean
    public ExitLetters exitLetters(Db db, Crypto crypto) {
        return new ExitLetters(db, crypto);
    }

    @Bean
    public ExitInterview exitInterview(Db db, Crypto crypto) {
        return new ExitInterview(db, crypto);
    }

    @Bean
    public PayrollConfig payrollConfig(Db db) {
        return new PayrollConfig(db);
    }

    @Bean
    public PayrollPay payrollPay(Db db) {
        return new PayrollPay(db);
    }

    @Bean
    public PayrollRuns payrollRuns(Db db, Crypto crypto) {
        return new PayrollRuns(db, crypto);
    }

    @Bean
    public ExitTasks exitTasks(Db db) {
        return new ExitTasks(db);
    }

    @Bean
    public LetterService letterService(Db db, Crypto crypto, DocumentService documents) {
        return new LetterService(db, crypto, documents);
    }

    @Bean
    public PolicyService policyService(Db db) {
        return new PolicyService(db);
    }

    @Bean
    public Notifications notifications(Db db) {
        return new Notifications(db);
    }

    @Bean
    public NumberSeries numberSeries(Db db) {
        return new NumberSeries(db);
    }

    @Bean
    public JoinerService joinerService(Db db, Crypto crypto) {
        return new JoinerService(db, crypto);
    }

    @Bean
    public OnboardingConfigService onboardingConfigService(Db db) {
        return new OnboardingConfigService(db);
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
