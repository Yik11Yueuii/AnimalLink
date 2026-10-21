package org.animallink.identity;

import org.animallink.identity.operations.AdminBootstrapCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminBootstrapCommandTest {
    @Test
    void rejectsNonInteractiveInvocationBeforeStartingSpring() {
        assertThat(System.console()).isNull();
        assertThatThrownBy(() -> AdminBootstrapCommand.main(new String[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Administrator initialization requires an interactive local console");
    }

    @Test
    void rejectsAllCommandLineArgumentsBeforeReadingCredentials() {
        assertThatThrownBy(() -> AdminBootstrapCommand.main(new String[]{"password-value"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Administrator initialization accepts no command-line arguments");
    }
}
