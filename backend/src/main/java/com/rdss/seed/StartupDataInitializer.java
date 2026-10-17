package com.rdss.seed;

import com.rdss.common.security.Role;
import com.rdss.config.RdssProperties;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs once at startup:
 * <ol>
 *   <li>Bootstraps an administrator when none exists and BOOTSTRAP_ADMIN_PASSWORD is set.</li>
 *   <li>Seeds synthetic demo data when SEED_DEMO_DATA=true (idempotent).</li>
 * </ol>
 */
@Component
public class StartupDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupDataInitializer.class);

    private final RdssProperties props;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final DemoDataSeeder demoDataSeeder;

    public StartupDataInitializer(RdssProperties props, AppUserRepository users, PasswordEncoder passwordEncoder,
                                  DemoDataSeeder demoDataSeeder) {
        this.props = props;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.demoDataSeeder = demoDataSeeder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        bootstrapAdmin();
        if (props.seed() != null && props.seed().demoData()) {
            String pw = props.seed().demoPassword();
            if (pw == null || pw.length() < 10) {
                throw new IllegalStateException(
                        "SEED_DEMO_DATA=true requires DEMO_USER_PASSWORD (at least 10 characters)");
            }
            demoDataSeeder.seed(passwordEncoder.encode(pw));
        }
    }

    private void bootstrapAdmin() {
        RdssProperties.Bootstrap b = props.bootstrap();
        if (b == null || b.adminPassword() == null || b.adminPassword().isBlank() || users.existsByRole(Role.ADMIN)) {
            return;
        }
        if (b.adminPassword().length() < 10) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must be at least 10 characters");
        }
        users.save(new AppUser(b.adminUsername(), b.adminEmail(), passwordEncoder.encode(b.adminPassword()),
                "System Administrator", Role.ADMIN));
        log.info("Bootstrapped initial administrator account '{}'", b.adminUsername());
    }
}
