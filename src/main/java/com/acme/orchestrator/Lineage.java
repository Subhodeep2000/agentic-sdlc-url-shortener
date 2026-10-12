package com.acme.orchestrator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Decision lineage: which committed task attempt consumed and produced which artifact versions, and why. */
public final class Lineage {
    public record Commit(String taskId, int attempt, String agent, List<String> consumed, List<String> produced,
                         List<String> decisions) {
    }

    private final List<Commit> commits = new ArrayList<>();

    public synchronized void record(Commit c) {
        commits.add(c);
    }

    public synchronized List<Commit> commits() {
        return List.copyOf(commits);
    }

    /** Upstream chain for an artifact ref such as {@code design@2}, most recent producer first. */
    public synchronized List<String> trace(String ref) {
        List<String> out = new ArrayList<>();
        walk(ref, new LinkedHashSet<>(), out, 0);
        return out;
    }

    private void walk(String ref, Set<String> seen, List<String> out, int depth) {
        if (!seen.add(ref)) {
            return;
        }
        for (int i = commits.size() - 1; i >= 0; i--) {
            Commit c = commits.get(i);
            if (c.produced().contains(ref)) {
                out.add("  ".repeat(depth) + ref + " <- " + c.taskId() + " (attempt " + c.attempt() + ", " + c.agent()
                        + ") consumed " + c.consumed());
                c.consumed().forEach(in -> walk(in, seen, out, depth + 1));
                return;
            }
        }
        out.add("  ".repeat(depth) + ref + " <- external input");
    }
}
