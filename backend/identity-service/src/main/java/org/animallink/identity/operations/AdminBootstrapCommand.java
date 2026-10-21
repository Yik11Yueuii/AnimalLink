package org.animallink.identity.operations;

import org.animallink.identity.application.AdminBootstrapService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.Console;
import java.nio.CharBuffer;
import java.util.Arrays;

/**
 * Explicit local-only operations command. It accepts no command-line values and requires a real console,
 * so the password is never supplied through shell history or process arguments.
 */
public final class AdminBootstrapCommand {
    private static final String CONFIRMATION = "CREATE FIRST ANIMALLINK ADMIN";

    private AdminBootstrapCommand() {
    }

    public static void main(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("Administrator initialization accepts no command-line arguments");
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Administrator initialization requires an interactive local console");
        }

        try (var context = new SpringApplicationBuilder(AdminBootstrapApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("admin-bootstrap")
                .properties("spring.main.banner-mode=off")
                .run()) {
            String displayName = console.readLine("Administrator display name: ");
            String username = console.readLine("Administrator username: ");
            String confirmation = console.readLine("Type '%s' to authorize initialization: ", CONFIRMATION);
            if (!CONFIRMATION.equals(confirmation)) {
                throw new IllegalStateException("Administrator initialization was not confirmed");
            }
            char[] password = console.readPassword("Administrator password: ");
            char[] repeated = console.readPassword("Repeat administrator password: ");
            try {
                if (password == null || repeated == null || !Arrays.equals(password, repeated)) {
                    throw new IllegalArgumentException("Password confirmation does not match");
                }
                var service = context.getBean(AdminBootstrapService.class);
                var authorization = AdminBootstrapService.LocalBootstrapAuthorization.confirmedInteractive(
                        "local:" + System.getProperty("user.name", "unknown"));
                var user = service.initialize(authorization, displayName, username, CharBuffer.wrap(password));
                console.printf("Administrator initialized successfully. User ID: %s%n", user.id());
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (repeated != null) Arrays.fill(repeated, '\0');
            }
        }
    }
}
