package io.agentic.shortener.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class Base62CodeGeneratorTest {

    @Test
    @Tag("SHORT-001")
    @DisplayName("SHORT-001: generated codes are 7 Base62 characters")
    void generatesBase62CodesOfConfiguredLength() {
        CodeGenerator generator = new Base62CodeGenerator(7, new SecureRandom());

        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.next()).hasSize(7).matches("[0-9A-Za-z]{7}");
        }
    }

    @Test
    @Tag("SHORT-001")
    @DisplayName("SHORT-001: codes are unpredictable and spread across the alphabet")
    void codesAreDistinctAndUseWholeAlphabet() {
        CodeGenerator generator = new Base62CodeGenerator(7, new SecureRandom());
        Set<String> codes = new HashSet<>();
        Set<Character> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            String code = generator.next();
            codes.add(code);
            code.chars().forEach(c -> seen.add((char) c));
        }

        assertThat(codes).hasSize(10_000);
        assertThat(seen).hasSize(62);
    }

    @Test
    @Tag("SHORT-001")
    @DisplayName("SHORT-001: same random source yields the same codes (injectable for tests)")
    void isDeterministicForSeededSource() {
        String first = new Base62CodeGenerator(7, new java.util.Random(42)).next();
        String second = new Base62CodeGenerator(7, new java.util.Random(42)).next();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @Tag("SHORT-001")
    @DisplayName("SHORT-001: rejects code lengths outside 3..32")
    void rejectsInvalidLength() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Base62CodeGenerator(2, new SecureRandom()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Base62CodeGenerator(33, new SecureRandom()));
    }
}
