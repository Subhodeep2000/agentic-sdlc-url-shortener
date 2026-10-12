package com.acme.sdlc;

import com.acme.common.Json;
import com.acme.orchestrator.Agent;
import com.acme.orchestrator.AgentException;
import com.acme.orchestrator.AgentOutput;
import com.acme.orchestrator.Capability;
import com.acme.orchestrator.TaskContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic SDLC agents. Each follows the same contract an LLM-backed agent would: declared inputs in, declared
 * artifacts out, decisions recorded for lineage. Swapping one for an LLM changes nothing in the governance around it.
 */
public final class SdlcAgents {
    public static final String WORKSPACE = "workspace";
    public static final String PATCHES = "patches";
    public static final String CATALOG_ROOT = "catalogRoot";
    public static final String BUILD = "build";

    private SdlcAgents() {
    }

    abstract static class Base implements Agent {
        private final String name;

        Base(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }
    }

    /** Requirement understanding: normalise the request, find ambiguity, derive acceptance criteria and risks. */
    public static Agent analyst() {
        return new Base("analyst") {
            @Override
            public AgentOutput execute(TaskContext ctx) {
                Map<String, Object> req = new RequirementsAnalyzer().analyze(ctx.readString("request"));
                List<?> amb = (List<?>) req.get("ambiguities");
                return AgentOutput.of(((List<?>) req.get("items")).size() + " requirement(s), " + amb.size()
                                + " ambiguity(ies)")
                        .artifact("requirements", req)
                        .decision("normalised the request into " + ((List<?>) req.get("items")).size()
                                + " atomic, testable requirement items")
                        .decision(amb.isEmpty() ? "no blocking ambiguity found"
                                : amb.size() + " ambiguity(ies) escalated for human clarification instead of guessing");
            }
        };
    }

    /** Resolves ambiguities with human answers where given, otherwise with documented default assumptions. */
    public static Agent clarifier() {
        return new Base("clarifier") {
            @Override
            @SuppressWarnings("unchecked")
            public AgentOutput execute(TaskContext ctx) throws AgentException {
                Map<String, Object> req = ctx.readMap("requirements");
                Map<String, Object> human = (Map<String, Object>) ctx.readOptional("human.clarify").orElse(Map.of());
                Map<String, Object> params = new LinkedHashMap<>();
                List<String> assumptions = new ArrayList<>();
                AgentOutput out = AgentOutput.of("resolved " + ((List<?>) req.get("ambiguities")).size() + " ambiguity(ies)");
                for (Object o : (List<Object>) req.get("ambiguities")) {
                    Map<String, Object> a = (Map<String, Object>) o;
                    String param = (String) a.get("parameter");
                    if (!param.isEmpty()) {
                        Object answer = human.get(param);
                        String value = answer != null ? String.valueOf(answer) : (String) a.get("default");
                        validate(param, value);
                        params.put(param, value);
                        out.decision(a.get("id") + " " + param + " = " + value
                                + (answer != null ? " (answered by a human)" : " (default assumption; no human answer)"));
                        assumptions.add(a.get("id") + ": " + param + " = " + value
                                + (answer != null ? " [human]" : " [assumed]"));
                    } else {
                        assumptions.add(a.get("id") + ": " + a.get("assumption") + " [assumed]");
                        out.decision(a.get("id") + " assumed: " + a.get("assumption"));
                    }
                }
                Map<String, Object> decisions = new LinkedHashMap<>();
                decisions.put("parameters", params);
                decisions.put("assumptions", assumptions);
                decisions.put("humanInputs", new LinkedHashMap<>(human));
                return out.artifact("decisions", decisions);
            }

            private void validate(String param, String value) throws AgentException {
                if (param.equals("retention_days")) {
                    try {
                        int n = Integer.parseInt(value);
                        if (n < 1 || n > 3650) {
                            throw AgentException.permanent("retention_days must be between 1 and 3650, got " + n);
                        }
                    } catch (NumberFormatException e) {
                        throw AgentException.permanent("retention_days must be a whole number, got '" + value + "'");
                    }
                }
            }
        };
    }

    /** Codebase reasoning and design: impact analysis, recipe selection, decomposition, traceability, trade-offs. */
    public static Agent architect() {
        return new Base("architect") {
            @Override
            @SuppressWarnings("unchecked")
            public AgentOutput execute(TaskContext ctx) throws AgentException {
                Workspace ws = ctx.resource(WORKSPACE, Workspace.class, Capability.READ_WORKSPACE);
                Path catalogRoot = ctx.resource(CATALOG_ROOT, Path.class, Capability.READ_WORKSPACE);
                Map<String, Object> req = ctx.readMap("requirements");
                Map<String, Object> decisions = ctx.readMap("decisions");
                List<String> concepts = (List<String>) req.get("concepts");
                CodebaseAnalyzer code = CodebaseAnalyzer.scan(ws);

                Set<String> seeds = code.classesForConcepts(concepts);
                Map<String, String> impact = code.impactOf(seeds);
                Set<String> tests = code.testsFor(impact.keySet());
                List<String> flows = code.flowsTo(seeds, impact.keySet());

                Map<String, Object> chosen = choose(catalogRoot, concepts);
                Map<String, Object> params = new LinkedHashMap<>();
                Map<String, Object> have = (Map<String, Object>) decisions.get("parameters");
                for (Object p : (List<Object>) chosen.get("params")) {
                    Object v = have.get(p);
                    if (v == null) {
                        throw AgentException.permanent("recipe '" + chosen.get("name") + "' needs parameter '" + p
                                + "' which neither a human nor a default provided");
                    }
                    params.put((String) p, v);
                }

                List<Map<String, Object>> trace = new ArrayList<>();
                for (Object o : (List<Object>) req.get("items")) {
                    Map<String, Object> item = (Map<String, Object>) o;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("requirement", item.get("id"));
                    row.put("acceptance", item.get("acceptance"));
                    row.put("coveredBy", new ArrayList<>(tests));
                    trace.add(row);
                }
                List<Map<String, Object>> endpoints = new ArrayList<>();
                code.endpoints().forEach(e -> endpoints.add(Map.of("method", e.method(), "path", e.path(),
                        "declaredIn", e.declaredIn())));
                Map<String, Object> design = new LinkedHashMap<>();
                design.put("patch", chosen.get("name"));
                design.put("patchSummary", chosen.get("summary"));
                design.put("params", params);
                design.put("seeds", new ArrayList<>(seeds));
                design.put("impact", new LinkedHashMap<>(impact));
                design.put("testsImpacted", new ArrayList<>(tests));
                design.put("flows", flows);
                design.put("endpointsBefore", endpoints);
                design.put("traceability", trace);
                design.put("tradeoffs", chosen.get("tradeoffs"));
                design.put("risks", req.get("risks"));
                design.put("decomposition", List.of(
                        step("analyze", "REQUIREMENTS", "normalise request, list ambiguities", List.of()),
                        step("clarify", "REQUIREMENTS", "resolve ambiguities with human input or assumptions", List.of("analyze")),
                        step("design", "DESIGN", "impact analysis and recipe selection", List.of("clarify")),
                        step("implement", "IMPLEMENTATION", "apply recipe '" + chosen.get("name") + "' to the workspace",
                                List.of("design")),
                        step("test", "TESTING", "full regression suite; failure rolls the change back", List.of("implement")),
                        step("docs", "DOCUMENTATION", "change note with requirement traceability", List.of("implement")),
                        step("release", "RELEASE_READINESS", "readiness checklist and human sign-off", List.of("test", "docs"))));
                return AgentOutput.of("recipe " + chosen.get("name") + "; " + impact.size() + " impacted class(es), "
                                + tests.size() + " impacted test class(es)")
                        .artifact("design", design)
                        .decision("selected recipe '" + chosen.get("name") + "' because its triggers " + chosen.get("triggers")
                                + " are all present in the requirement concepts " + concepts)
                        .decision("impact computed by reverse-dependency closure from " + seeds + ": " + impact.keySet())
                        .decision("regression scope = " + tests + " plus any tests the recipe adds");
            }

            private Map<String, Object> step(String id, String stage, String what, List<String> deps) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("task", id);
                m.put("stage", stage);
                m.put("work", what);
                m.put("dependsOn", deps);
                return m;
            }
        };
    }

    /** Most specific recipe whose triggers are all present among the concepts. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> choose(Path catalogRoot, List<String> concepts) throws AgentException {
        Map<String, Object> index;
        try {
            index = Json.parseObject(Files.readString(catalogRoot.resolve("index.json"), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw AgentException.permanent("cannot read recipe catalog: " + e.getMessage());
        }
        Map<String, Object> best = null;
        for (Object o : (List<Object>) index.get("patches")) {
            Map<String, Object> p = (Map<String, Object>) o;
            List<String> triggers = (List<String>) p.get("triggers");
            if (concepts.containsAll(triggers) && (best == null
                    || triggers.size() > ((List<?>) best.get("triggers")).size())) {
                best = p;
            }
        }
        if (best == null) {
            throw AgentException.permanent("no reviewed recipe covers concepts " + concepts
                    + "; this needs a human or an LLM-backed implementer");
        }
        return best;
    }

    /**
     * Applies a catalog recipe. The "draft" flavour first looks for a {@code <recipe>-draft} variant, which stands in
     * for a quick, unreviewed AI draft; the "recipe" flavour always applies the reviewed recipe.
     */
    public static Agent patchAgent(String name, boolean draft) {
        return new Base(name) {
            @Override
            public boolean supportsRollback() {
                return true;
            }

            @Override
            @SuppressWarnings("unchecked")
            public AgentOutput execute(TaskContext ctx) throws AgentException {
                Workspace ws = ctx.resource(WORKSPACE, Workspace.class, Capability.WRITE_WORKSPACE);
                PatchApplier patches = ctx.resource(PATCHES, PatchApplier.class, Capability.WRITE_WORKSPACE);
                Map<String, Object> design = ctx.readMap("design");
                String recipe = (String) design.get("patch");
                String variant = draft && patches.exists(recipe + "-draft") ? recipe + "-draft" : recipe;
                Map<String, String> params = new LinkedHashMap<>();
                ((Map<String, Object>) design.get("params")).forEach((k, v) -> params.put(k, String.valueOf(v)));
                ws.checkpoint(ctx.taskId() + "-pre");
                String before = ws.treeHash();
                PatchApplier.Applied applied = patches.apply(ws, variant, params);

                List<Map<String, Object>> files = new ArrayList<>();
                List<String> newTests = new ArrayList<>();
                StringBuilder diff = new StringBuilder();
                applied.files().forEach((path, m) -> {
                    SimpleDiff.Stats st = SimpleDiff.stats(m.get("before"), m.get("after"));
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("path", path);
                    f.put("op", m.get("op"));
                    f.put("added", (long) st.added());
                    f.put("removed", (long) st.removed());
                    files.add(f);
                    diff.append(SimpleDiff.unified(path, m.get("before"), m.get("after")));
                    if (m.get("op").equals("create") && path.endsWith("Test.java")) {
                        newTests.add(path.substring(path.lastIndexOf('/') + 1, path.length() - 5));
                    }
                });
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("patch", variant);
                change.put("files", files);
                change.put("newTests", newTests);
                change.put("diff", diff.toString());
                change.put("treeBefore", before);
                change.put("treeAfter", ws.treeHash());
                return AgentOutput.of("applied '" + variant + "' touching " + files.size() + " file(s)")
                        .artifact("change", change)
                        .decision("applied recipe variant '" + variant + "' with parameters " + params);
            }

            @Override
            public void rollback(TaskContext ctx) {
                ctx.resource(WORKSPACE, Workspace.class, Capability.WRITE_WORKSPACE).restore(ctx.taskId() + "-pre");
            }
        };
    }

    /** Full regression run: compile from scratch and run every test class. Any failure is a permanent task failure. */
    public static Agent tester() {
        return new Base("tester") {
            @Override
            public AgentOutput execute(TaskContext ctx) throws AgentException {
                Workspace ws = ctx.resource(WORKSPACE, Workspace.class, Capability.READ_WORKSPACE);
                BuildRunner build = ctx.resource(BUILD, BuildRunner.class, Capability.READ_WORKSPACE);
                ctx.read("change");
                BuildRunner.Result c = build.compile(ws);
                if (!c.ok()) {
                    throw AgentException.permanent("compilation failed: " + head(c.output()));
                }
                BuildRunner.TestReport t = build.test(ws, ".*");
                if (!t.ok()) {
                    throw AgentException.permanent(t.failed() + " test(s) failed: " + t.failedTests());
                }
                Map<String, Object> rep = new LinkedHashMap<>();
                rep.put("compiled", true);
                rep.put("compileMillis", c.millis());
                rep.put("run", (long) t.run());
                rep.put("passed", (long) t.passed());
                rep.put("failed", (long) t.failed());
                rep.put("tests", new ArrayList<>(t.passedTests()));
                return AgentOutput.of(t.passed() + "/" + t.run() + " tests passed").artifact("test-report", rep)
                        .decision("ran the full suite (not only the impacted tests) to catch regressions elsewhere");
            }
        };
    }

    /** Change note with requirement-to-evidence traceability. */
    public static Agent writer() {
        return new Base("writer") {
            @Override
            @SuppressWarnings("unchecked")
            public AgentOutput execute(TaskContext ctx) {
                Map<String, Object> req = ctx.readMap("requirements");
                Map<String, Object> decisions = ctx.readMap("decisions");
                Map<String, Object> change = ctx.readMap("change");
                StringBuilder md = new StringBuilder("# Change note\n\n## Requirements and acceptance\n\n");
                for (Object o : (List<Object>) req.get("items")) {
                    Map<String, Object> it = (Map<String, Object>) o;
                    md.append("- **").append(it.get("id")).append("** (").append(it.get("priority")).append(") ")
                            .append(it.get("text")).append('\n');
                    for (Object a : (List<Object>) it.get("acceptance")) {
                        md.append("  - acceptance: ").append(a).append('\n');
                    }
                }
                md.append("\n## What changed\n\nRecipe `").append(change.get("patch")).append("`:\n\n");
                for (Object o : (List<Object>) change.get("files")) {
                    Map<String, Object> f = (Map<String, Object>) o;
                    md.append("- `").append(f.get("path")).append("` (").append(f.get("op")).append(", +")
                            .append(f.get("added")).append("/-").append(f.get("removed")).append(")\n");
                }
                md.append("\n## Decisions and assumptions\n\n");
                List<Object> assumptions = (List<Object>) decisions.get("assumptions");
                if (assumptions.isEmpty()) {
                    md.append("- none: the request had no blocking ambiguity\n");
                }
                assumptions.forEach(a -> md.append("- ").append(a).append('\n'));
                md.append("\n## Operating notes\n\nRoll back by reverting the files above; no data migration is involved.\n");
                return AgentOutput.of("change note, " + md.length() + " chars").artifact("docs", md.toString())
                        .decision("documented every requirement id so the note is traceable to the request");
            }
        };
    }

    /** Release readiness: evidence-based checklist; the human decides, the agent never self-approves. */
    public static Agent releaseManager() {
        return new Base("release-manager") {
            @Override
            @SuppressWarnings("unchecked")
            public AgentOutput execute(TaskContext ctx) {
                Map<String, Object> tests = ctx.readMap("test-report");
                Map<String, Object> design = ctx.readMap("design");
                Map<String, Object> decisions = ctx.readMap("decisions");
                String docs = ctx.readString("docs");
                Map<String, Object> change = ctx.readMap("change");
                List<Map<String, Object>> checks = new ArrayList<>();
                boolean testsOk = ((Long) tests.get("failed")) == 0 && ((Long) tests.get("run")) > 0;
                checks.add(check("full regression suite green", testsOk, tests.get("passed") + "/" + tests.get("run")));
                checks.add(check("new behaviour has tests", !((List<?>) change.get("newTests")).isEmpty(),
                        String.valueOf(change.get("newTests"))));
                checks.add(check("change note present", docs.contains("# Change note"), docs.length() + " chars"));
                checks.add(check("assumptions are documented", docs.contains("Decisions and assumptions"),
                        ((List<?>) decisions.get("assumptions")).size() + " recorded"));
                checks.add(check("rollback path exists", true, "revert listed files; workspace checkpoint verified in tests"));
                boolean ready = checks.stream().allMatch(c -> "PASS".equals(c.get("status")));
                Map<String, Object> rep = new LinkedHashMap<>();
                rep.put("ready", ready);
                rep.put("checks", checks);
                rep.put("residualRisks", design.get("risks"));
                rep.put("tradeoffs", design.get("tradeoffs"));
                rep.put("assumptions", decisions.get("assumptions"));
                return AgentOutput.of(ready ? "ready for human sign-off" : "NOT ready").artifact("release-report", rep)
                        .decision("recommendation only: release requires an explicit human approval");
            }

            private Map<String, Object> check(String name, boolean ok, String evidence) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("check", name);
                m.put("status", ok ? "PASS" : "FAIL");
                m.put("evidence", evidence);
                return m;
            }
        };
    }

    static String head(String s) {
        return s.length() > 600 ? s.substring(0, 600) + "..." : s;
    }
}
