package io.agentic.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ContentHashTest {

    @Test
    void hashesKnownVector() {
        assertThat(ContentHash.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void isStableAndSensitiveToChange() {
        assertThat(ContentHash.sha256Hex("spec v1")).isEqualTo(ContentHash.sha256Hex("spec v1"));
        assertThat(ContentHash.sha256Hex("spec v1")).isNotEqualTo(ContentHash.sha256Hex("spec v2"));
    }

    @Test
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> ContentHash.sha256Hex(null));
    }

    @Test
    void unknownAlgorithmFailsLoudly() {
        assertThatIllegalStateException()
                .isThrownBy(() -> ContentHash.digestFor("NOT-A-REAL-ALGORITHM"))
                .withMessageContaining("NOT-A-REAL-ALGORITHM");
    }
}
