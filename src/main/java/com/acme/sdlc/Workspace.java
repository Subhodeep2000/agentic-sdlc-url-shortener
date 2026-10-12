package com.acme.sdlc;

import com.acme.common.Hashing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * A sandboxed source tree the agents work on. Paths are validated to stay inside the root (no traversal) and the whole
 * tree can be checkpointed and restored, which is what makes agent changes reversible. Build output ({@code out/}) is
 * excluded from checkpoints and hashes.
 */
public final class Workspace {
    private final Path root;
    private final Map<String, Map<String, String>> checkpoints = new ConcurrentHashMap<>();

    public Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Seeds a workspace with the given source subtrees of {@code repo} (e.g. {@code src/main/java/com/acme/shortener}). */
    public static Workspace seed(Path workRoot, Path repo, List<String> subtrees) {
        Workspace ws = new Workspace(workRoot);
        for (String sub : subtrees) {
            Path base = repo.resolve(sub);
            try (Stream<Path> s = Files.walk(base)) {
                for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    ws.write(repo.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return ws;
    }

    public Path root() {
        return root;
    }

    public Path outDir() {
        return root.resolve("out");
    }

    public Path resolve(String rel) {
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace: " + rel);
        }
        return p;
    }

    public synchronized void write(String rel, String content) {
        Path p = resolve(rel);
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String read(String rel) {
        try {
            return Files.readString(resolve(rel), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> files() {
        return new ArrayList<>(contents().keySet());
    }

    public List<String> javaFiles(String prefix) {
        return files().stream().filter(f -> f.startsWith(prefix) && f.endsWith(".java")).toList();
    }

    public synchronized Map<String, String> contents() {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith("out/")) {
                    m.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return m;
    }

    /** Content hash of the source tree: equal hashes mean byte-identical sources. */
    public String treeHash() {
        StringBuilder sb = new StringBuilder();
        contents().forEach((k, v) -> sb.append(k).append(':').append(Hashing.sha256(v)).append('\n'));
        return Hashing.sha256(sb.toString());
    }

    public void checkpoint(String label) {
        checkpoints.put(label, contents());
    }

    /** Restores the tree exactly to the checkpoint (extra files removed, changed files rewritten, out/ cleared). */
    public synchronized void restore(String label) {
        Map<String, String> snap = checkpoints.get(label);
        if (snap == null) {
            throw new IllegalStateException("no checkpoint '" + label + "'");
        }
        Map<String, String> now = contents();
        try {
            for (String f : now.keySet()) {
                if (!snap.containsKey(f)) {
                    Files.deleteIfExists(resolve(f));
                }
            }
            deleteRecursively(outDir());
            for (Map.Entry<String, String> e : snap.entrySet()) {
                if (!e.getValue().equals(now.get(e.getKey()))) {
                    write(e.getKey(), e.getValue());
                }
            }
            try (Stream<Path> s = Files.walk(root)) {
                List<Path> dirs = new ArrayList<>(s.filter(Files::isDirectory).toList());
                dirs.sort(Comparator.reverseOrder());
                for (Path d : dirs) {
                    if (!d.equals(root)) {
                        try (Stream<Path> kids = Files.list(d)) {
                            if (kids.findAny().isEmpty()) {
                                Files.delete(d);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.exists(p)) {
            try (Stream<Path> s = Files.walk(p)) {
                for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(x);
                }
            }
        }
    }
}
