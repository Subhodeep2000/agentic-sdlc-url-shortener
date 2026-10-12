package com.acme.sdlc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static codebase reasoning for brownfield work, from source text only: class inventory, class-reference graph, concept
 * index (from {@code @concepts} Javadoc tags), HTTP routes declared in Javadoc, and reverse-dependency closure for change
 * impact. References are name-based (no type resolution), so impact is a conservative over-approximation, which is the
 * safe direction; reflection is invisible.
 */
public final class CodebaseAnalyzer {
    public record ClassInfo(String name, String path, boolean test, Set<String> concepts, int lines) {
    }

    public record Endpoint(String method, String path, String description, String declaredIn) {
    }

    private static final Pattern CONCEPTS = Pattern.compile("@concepts\\s+([^\\n*]+)");
    private static final Pattern ROUTE = Pattern.compile("^\\s*\\*\\s+(GET|POST|PUT|DELETE|PATCH)\\s+(\\S+)\\s+(.*)$",
            Pattern.MULTILINE);

    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    private final Map<String, Set<String>> references = new LinkedHashMap<>();
    private final List<Endpoint> endpoints = new ArrayList<>();

    private CodebaseAnalyzer() {
    }

    public static CodebaseAnalyzer scan(Workspace ws) {
        CodebaseAnalyzer a = new CodebaseAnalyzer();
        Map<String, String> sources = new LinkedHashMap<>();
        for (String f : ws.javaFiles("src/")) {
            String src = ws.read(f);
            String name = f.substring(f.lastIndexOf('/') + 1, f.length() - 5);
            Set<String> concepts = new TreeSet<>();
            Matcher cm = CONCEPTS.matcher(src);
            while (cm.find()) {
                for (String c : cm.group(1).trim().split("\\s+")) {
                    concepts.add(c.toLowerCase());
                }
            }
            boolean test = f.startsWith("src/test/");
            a.classes.put(name, new ClassInfo(name, f, test, concepts, src.split("\n", -1).length));
            sources.put(name, src);
            if (!test) {
                Matcher rm = ROUTE.matcher(src);
                while (rm.find()) {
                    a.endpoints.add(new Endpoint(rm.group(1), rm.group(2), rm.group(3).trim(), name));
                }
            }
        }
        sources.forEach((name, src) -> {
            Set<String> refs = new LinkedHashSet<>();
            for (String other : a.classes.keySet()) {
                if (!other.equals(name) && Pattern.compile("\\b" + Pattern.quote(other) + "\\b").matcher(src).find()) {
                    refs.add(other);
                }
            }
            a.references.put(name, refs);
        });
        return a;
    }

    public Map<String, ClassInfo> classes() {
        return classes;
    }

    public List<Endpoint> endpoints() {
        return endpoints;
    }

    /** Main (non-test) classes tagged with at least one of the given concepts. */
    public Set<String> classesForConcepts(Collection<String> concepts) {
        Set<String> out = new LinkedHashSet<>();
        classes.values().stream().filter(c -> !c.test() && c.concepts().stream().anyMatch(concepts::contains))
                .forEach(c -> out.add(c.name()));
        return out;
    }

    /** Transitive reverse-dependency closure over main classes, with the reason each class is impacted. */
    public Map<String, String> impactOf(Set<String> seeds) {
        Map<String, String> why = new LinkedHashMap<>();
        Deque<String> work = new ArrayDeque<>();
        seeds.forEach(s -> {
            why.put(s, "tagged with a requirement concept");
            work.add(s);
        });
        while (!work.isEmpty()) {
            String cur = work.poll();
            references.forEach((cls, refs) -> {
                if (!classes.get(cls).test() && refs.contains(cur) && !why.containsKey(cls)) {
                    why.put(cls, "references " + cur);
                    work.add(cls);
                }
            });
        }
        return why;
    }

    /** Test classes covering the given main classes (by {@code XTest} naming or by referencing X). */
    public Set<String> testsFor(Collection<String> mainClasses) {
        Set<String> out = new TreeSet<>();
        for (ClassInfo c : classes.values()) {
            if (c.test() && c.name().endsWith("Test") && mainClasses.stream().anyMatch(m -> c.name().startsWith(m)
                    || references.getOrDefault(c.name(), Set.of()).contains(m))) {
                out.add(c.name());
            }
        }
        return out;
    }

    /** Reference chains from API-layer classes (those declaring routes) down to the seeds. */
    public List<String> flowsTo(Set<String> seeds, Set<String> within) {
        Set<String> apiLayer = new LinkedHashSet<>();
        endpoints.forEach(e -> apiLayer.add(e.declaredIn()));
        List<String> flows = new ArrayList<>();
        for (String entry : apiLayer) {
            for (String seed : seeds) {
                List<String> p = path(entry, seed, within);
                if (p != null && p.size() > 1) {
                    flows.add(String.join(" -> ", p));
                }
            }
        }
        return flows;
    }

    private List<String> path(String from, String to, Set<String> within) {
        Deque<List<String>> q = new ArrayDeque<>();
        q.add(List.of(from));
        Set<String> seen = new LinkedHashSet<>(List.of(from));
        while (!q.isEmpty()) {
            List<String> p = q.poll();
            String last = p.get(p.size() - 1);
            if (last.equals(to)) {
                return p;
            }
            for (String next : references.getOrDefault(last, Set.of())) {
                if (within.contains(next) && !classes.get(next).test() && seen.add(next)) {
                    List<String> np = new ArrayList<>(p);
                    np.add(next);
                    q.add(np);
                }
            }
        }
        return null;
    }
}
