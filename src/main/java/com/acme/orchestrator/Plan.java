package com.acme.orchestrator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Explicit dependency graph (DAG). Validation guarantees unique ids, known dependencies, no cycles, one producer per
 * artifact, and that every read of a produced artifact is synchronised by a dependency path (no racy cross-branch read).
 */
public final class Plan {
    private final Map<String, TaskSpec> tasks = new LinkedHashMap<>();
    private final Set<String> externalInputs = new LinkedHashSet<>();

    public Plan externalInputs(String... keys) {
        externalInputs.addAll(List.of(keys));
        return this;
    }

    public Set<String> externalInputs() {
        return externalInputs;
    }

    public Plan add(TaskSpec t) {
        if (tasks.putIfAbsent(t.id(), t) != null) {
            throw new PlanException("duplicate task id '" + t.id() + "'");
        }
        return this;
    }

    public Plan copy() {
        Plan p = new Plan();
        p.tasks.putAll(tasks);
        p.externalInputs.addAll(externalInputs);
        return p;
    }

    public TaskSpec get(String id) {
        TaskSpec t = tasks.get(id);
        if (t == null) {
            throw new PlanException("unknown task '" + id + "'");
        }
        return t;
    }

    public List<TaskSpec> tasks() {
        return List.copyOf(tasks.values());
    }

    public void validate() {
        for (TaskSpec t : tasks.values()) {
            for (String d : t.dependsOn()) {
                if (!tasks.containsKey(d)) {
                    throw new PlanException("task " + t.id() + " depends on unknown task " + d);
                }
                if (d.equals(t.id())) {
                    throw new PlanException("task " + t.id() + " depends on itself");
                }
            }
        }
        topologicalOrder();
        Map<String, String> producer = new HashMap<>();
        for (TaskSpec t : tasks.values()) {
            for (String w : t.writes()) {
                String prev = producer.putIfAbsent(w, t.id());
                if (prev != null) {
                    throw new PlanException("artifact '" + w + "' has two producers: " + prev + " and " + t.id());
                }
            }
        }
        for (TaskSpec t : tasks.values()) {
            Set<String> anc = ancestors(t.id());
            for (String r : t.reads()) {
                String p = producer.get(r);
                if (p == null) {
                    if (!externalInputs.contains(r) && !r.startsWith("human.")) {
                        throw new PlanException("task " + t.id() + " reads '" + r + "' which nothing produces");
                    }
                } else if (!anc.contains(p)) {
                    throw new PlanException("task " + t.id() + " reads '" + r + "' from " + p
                            + " without a dependency path (unsynchronised read)");
                }
            }
        }
    }

    public List<String> topologicalOrder() {
        Map<String, Integer> indeg = new LinkedHashMap<>();
        Map<String, List<String>> out = new HashMap<>();
        tasks.keySet().forEach(k -> indeg.put(k, 0));
        for (TaskSpec t : tasks.values()) {
            for (String d : t.dependsOn()) {
                if (tasks.containsKey(d)) {
                    indeg.merge(t.id(), 1, Integer::sum);
                    out.computeIfAbsent(d, k -> new ArrayList<>()).add(t.id());
                }
            }
        }
        Deque<String> q = new ArrayDeque<>();
        indeg.forEach((k, v) -> {
            if (v == 0) {
                q.add(k);
            }
        });
        List<String> order = new ArrayList<>();
        while (!q.isEmpty()) {
            String n = q.poll();
            order.add(n);
            for (String m : out.getOrDefault(n, List.of())) {
                if (indeg.merge(m, -1, Integer::sum) == 0) {
                    q.add(m);
                }
            }
        }
        if (order.size() != tasks.size()) {
            List<String> cyc = new ArrayList<>(tasks.keySet());
            cyc.removeAll(order);
            throw new PlanException("dependency cycle involving " + cyc);
        }
        return order;
    }

    public Set<String> ancestors(String id) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(get(id).dependsOn());
        while (!stack.isEmpty()) {
            String n = stack.pop();
            if (seen.add(n) && tasks.containsKey(n)) {
                stack.addAll(tasks.get(n).dependsOn());
            }
        }
        return seen;
    }

    public Set<String> descendants(String id) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> frontier = new LinkedHashSet<>(Set.of(id));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (TaskSpec t : tasks.values()) {
                if (!seen.contains(t.id()) && t.dependsOn().stream().anyMatch(frontier::contains)) {
                    seen.add(t.id());
                    frontier.add(t.id());
                    grew = true;
                }
            }
        }
        return seen;
    }

    /** Longest weighted path: the lower bound on end-to-end latency with unlimited parallelism. */
    public List<String> criticalPath(ToLongFunction<String> weight) {
        Map<String, Long> best = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        for (String id : topologicalOrder()) {
            long base = 0;
            String bp = null;
            for (String d : get(id).dependsOn()) {
                if (bp == null || best.get(d) > base) {
                    base = best.get(d);
                    bp = d;
                }
            }
            best.put(id, base + weight.applyAsLong(id));
            if (bp != null) {
                prev.put(id, bp);
            }
        }
        String end = best.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        List<String> path = new ArrayList<>();
        for (String n = end; n != null; n = prev.get(n)) {
            path.add(0, n);
        }
        return path;
    }
}
