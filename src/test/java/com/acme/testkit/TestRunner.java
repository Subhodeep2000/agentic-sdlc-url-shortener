package com.acme.testkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Discovers and runs {@link Test} methods. Usage: {@code TestRunner <classesDir> [pkgPrefix] [classRegex]}. */
public final class TestRunner {
    private TestRunner() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "out");
        String prefix = args.length > 1 ? args[1] : "";
        Pattern filter = Pattern.compile(args.length > 2 && !args[2].isEmpty() ? args[2] : ".*");
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String rel = root.relativize(p).toString();
                String n = rel.substring(0, rel.length() - 6).replace('/', '.');
                String simple = n.substring(n.lastIndexOf('.') + 1);
                if (n.startsWith(prefix) && !n.contains("$") && simple.endsWith("Test")
                        && filter.matcher(simple).matches()) {
                    names.add(n);
                }
            });
        }
        names.sort(Comparator.naturalOrder());
        int run = 0;
        int failed = 0;
        for (String cn : names) {
            Class<?> c = Class.forName(cn);
            if (Modifier.isAbstract(c.getModifiers())) {
                continue;
            }
            List<Method> ms = new ArrayList<>();
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Test.class)) {
                    ms.add(m);
                }
            }
            ms.sort(Comparator.comparing(Method::getName));
            for (Method m : ms) {
                run++;
                String label = c.getSimpleName() + "." + m.getName();
                try {
                    m.invoke(c.getDeclaredConstructor().newInstance());
                    System.out.println("PASS  " + label);
                } catch (InvocationTargetException e) {
                    failed++;
                    System.out.println("FAIL  " + label + " -> " + e.getCause());
                    StackTraceElement[] st = e.getCause().getStackTrace();
                    for (int i = 0; i < Math.min(4, st.length); i++) {
                        System.out.println("        at " + st[i]);
                    }
                } catch (ReflectiveOperationException e) {
                    failed++;
                    System.out.println("FAIL  " + label + " -> cannot instantiate: " + e);
                }
            }
        }
        System.out.printf("%nTests run: %d, passed: %d, failed: %d%n", run, run - failed, failed);
        System.exit(failed == 0 && run > 0 ? 0 : 1);
    }
}
