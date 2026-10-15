package dev.linkkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class Base62Test {

    @Test
    void encodesKnownValues() {
        assertEquals("0", Base62.encode(0));
        assertEquals("Z", Base62.encode(35));
        assertEquals("10", Base62.encode(62));
    }

    @Test
    void rejectsNegatives() {
        assertThrows(IllegalArgumentException.class, () -> Base62.encode(-1));
    }
}
