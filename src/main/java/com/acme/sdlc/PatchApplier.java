package com.acme.sdlc;

import com.acme.common.Json;
import com.acme.orchestrator.AgentException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a reviewed change recipe ({@code patch.json} plus template files) to a workspace.
 *
 * <p>Safety: every edit must match exactly once (drift is a hard failure, never a fuzzy apply); paths stay inside the
 * workspace; templates stay inside the catalog; unresolved {@code ${placeholders}} fail the patch; nothing is written
 * unless the whole patch validates (all-or-nothing).
 */
public final class PatchApplier {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-z_]+)}");
    private final Path catalogRoot;

    public PatchApplier(Path catalogRoot) {
        this.catalogRoot = catalogRoot.toAbsolutePath().normalize();
    }

    public boolean exists(String patchName) {
        return Files.isRegularFile(catalogRoot.resolve("patches").resolve(patchName).resolve("patch.json"));
    }

    /** {@code files}: path -> {op, before, after}. */
    public record Applied(String name, String description, Map<String, Map<String, String>> files) {
    }

    @SuppressWarnings("unchecked")
    public Applied apply(Workspace ws, String patchName, Map<String, String> params) throws AgentException {
        Path dir = catalogRoot.resolve("patches").resolve(patchName).normalize();
        if (!dir.startsWith(catalogRoot) || !Files.isRegularFile(dir.resolve("patch.json"))) {
            throw AgentException.permanent("unknown patch '" + patchName + "'");
        }
        Map<String, Object> spec;
        try {
            spec = Json.parseObject(Files.readString(dir.resolve("patch.json"), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw AgentException.permanent("unreadable patch.json: " + e.getMessage());
        }
        Map<String, String> staged = new LinkedHashMap<>(ws.contents());
        Map<String, Map<String, String>> report = new LinkedHashMap<>();
        for (Object o : (List<Object>) spec.getOrDefault("edits", List.of())) {
            Map<String, Object> edit = (Map<String, Object>) o;
            String file = (String) edit.get("file");
            String before = staged.get(file);
            if (before == null) {
                throw AgentException.permanent("patch conflict: " + file + " does not exist");
            }
            String find = sub(read(dir, (String) edit.get("findFile")), params);
            String replace = sub(read(dir, (String) edit.get("replaceFile")), params);
            int first = before.indexOf(find);
            if (first < 0) {
                throw AgentException.permanent("patch conflict: expected text not found in " + file
                        + " (code drifted since the patch was written)");
            }
            if (before.indexOf(find, first + 1) >= 0) {
                throw AgentException.permanent("patch ambiguity: expected text appears more than once in " + file);
            }
            String after = before.substring(0, first) + replace + before.substring(first + find.length());
            staged.put(file, after);
            Map<String, String> r = report.computeIfAbsent(file, k -> new LinkedHashMap<>(Map.of("op", "edit", "before", before)));
            r.put("after", after);
        }
        for (Object o : (List<Object>) spec.getOrDefault("create", List.of())) {
            Map<String, Object> c = (Map<String, Object>) o;
            String file = (String) c.get("file");
            if (staged.containsKey(file)) {
                throw AgentException.permanent("patch conflict: " + file + " already exists");
            }
            String content = sub(read(dir, (String) c.get("from")), params);
            staged.put(file, content);
            report.put(file, new LinkedHashMap<>(Map.of("op", "create", "before", "", "after", content)));
        }
        for (String f : report.keySet()) {
            try {
                ws.resolve(f);
            } catch (IllegalArgumentException e) {
                throw AgentException.permanent("patch targets a path outside the workspace: " + f);
            }
        }
        report.forEach((f, m) -> ws.write(f, m.get("after")));
        return new Applied((String) spec.getOrDefault("name", patchName),
                (String) spec.getOrDefault("description", ""), report);
    }

    private String read(Path dir, String rel) throws AgentException {
        if (rel == null) {
            throw AgentException.permanent("patch entry lacks a file reference");
        }
        Path p = dir.resolve(rel).normalize();
        if (!p.startsWith(catalogRoot) || !Files.isRegularFile(p)) {
            throw AgentException.permanent("template not found or outside catalog: " + rel);
        }
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw AgentException.permanent("cannot read " + rel);
        }
    }

    private static String sub(String s, Map<String, String> params) throws AgentException {
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder sb = new StringBuilder();
        List<String> missing = new ArrayList<>();
        while (m.find()) {
            String v = params.get(m.group(1));
            if (v == null) {
                missing.add(m.group(1));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(v));
            }
        }
        if (!missing.isEmpty()) {
            throw AgentException.permanent("patch needs parameter(s) " + missing + " that the requirements did not provide");
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
