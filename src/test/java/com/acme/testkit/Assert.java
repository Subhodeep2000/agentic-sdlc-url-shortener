package com.acme.testkit;

import java.util.Objects;

public final class Assert {
    private Assert() {
    }

    public static void assertTrue(boolean c, String m) {
        if (!c) {
            throw new AssertionError(m);
        }
    }

    public static void assertTrue(boolean c) {
        assertTrue(c, "expected true");
    }

    public static void assertFalse(boolean c, String m) {
        assertTrue(!c, m);
    }

    public static void assertFalse(boolean c) {
        assertTrue(!c, "expected false");
    }

    public static void assertEquals(Object e, Object a) {
        assertEquals(e, a, "values differ");
    }

    public static void assertEquals(Object e, Object a, String m) {
        if (!Objects.equals(e, a)) {
            throw new AssertionError(m + ": expected <" + e + "> but was <" + a + ">");
        }
    }

    public static void assertNotNull(Object o, String m) {
        if (o == null) {
            throw new AssertionError(m);
        }
    }

    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static <T extends Throwable> T assertThrows(Class<T> type, ThrowingRunnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }
}
