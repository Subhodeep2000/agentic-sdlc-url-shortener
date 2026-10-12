package com.acme.sdlc;

import com.acme.orchestrator.Approval;
import com.acme.orchestrator.Capability;
import com.acme.orchestrator.Gate;
import com.acme.orchestrator.GateResult;
import com.acme.orchestrator.Plan;
import com.acme.orchestrator.Stage;
import com.acme.orchestrator.TaskSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The SDLC workflow as an explicit dependency graph with entry/exit gates:
 *
 * <pre>
 * analyze -> clarify -> design -> implement -> test ----\
 *                                          \-> docs ----+-> release
 * </pre>
 *
 * test and docs run in parallel and synchronise at release. Human checkpoints: clarify (only when ambiguity exists)
 * and release sign-off. implement is the only mutating task; test failure rolls it back.
 */
public final class SdlcPlan {
    private SdlcPlan() {
    }

    @SuppressWarnings("unchecked")
    public static Plan build() {
        Gate requirementsOk = in -> {
            Map<String, Object> r = (Map<String, Object>) in.output().artifacts().get("requirements");
            List<String> bad = new ArrayList<>();
            for (Object o : (List<Object>) r.get("items")) {
                Map<String, Object> it = (Map<String, Object>) o;
                if (((List<?>) it.get("acceptance")).isEmpty() && !((List<?>) it.get("concepts")).isEmpty()) {
                    bad.add(it.get("id") + " has concepts but no acceptance criteria");
                }
            }
            return bad.isEmpty() ? GateResult.pass("requirements-testable") : GateResult.fail("requirements-testable", bad);
        };
        Gate implementOk = in -> {
            Workspace ws = in.resource(SdlcAgents.WORKSPACE, Workspace.class);
            BuildRunner build = in.resource(SdlcAgents.BUILD, BuildRunner.class);
            Map<String, Object> design = (Map<String, Object>) in.blackboard().latest("design").orElseThrow().value();
            Map<String, Object> change = (Map<String, Object>) in.output().artifacts().get("change");
            Set<String> targeted = new LinkedHashSet<>((List<String>) design.get("testsImpacted"));
            targeted.addAll((List<String>) change.get("newTests"));
            BuildRunner.Result c = build.compile(ws);
            if (!c.ok()) {
                return GateResult.fail("compiles-and-targeted-tests", "compilation failed: " + SdlcAgents.head(c.output()));
            }
            BuildRunner.TestReport t = build.test(ws, String.join("|", targeted));
            return t.ok() ? GateResult.pass("compiles-and-targeted-tests (" + t.passed() + " tests)")
                    : GateResult.fail("compiles-and-targeted-tests", t.failed() + " targeted test(s) failed: " + t.failedTests());
        };
        Gate testsGreen = in -> {
            Map<String, Object> r = (Map<String, Object>) in.output().artifacts().get("test-report");
            return ((Long) r.get("failed")) == 0 && ((Long) r.get("run")) > 0 ? GateResult.pass("tests-green")
                    : GateResult.fail("tests-green", "test report shows failures");
        };
        Gate docsTraceable = in -> {
            Map<String, Object> r = (Map<String, Object>) in.blackboard().latest("requirements").orElseThrow().value();
            String docs = (String) in.output().artifacts().get("docs");
            List<String> missing = new ArrayList<>();
            for (Object o : (List<Object>) r.get("items")) {
                String id = (String) ((Map<String, Object>) o).get("id");
                if (!docs.contains("**" + id + "**")) {
                    missing.add("docs do not mention " + id);
                }
            }
            return missing.isEmpty() ? GateResult.pass("docs-traceable") : GateResult.fail("docs-traceable", missing);
        };
        Gate releaseReady = in -> {
            Map<String, Object> r = (Map<String, Object>) in.output().artifacts().get("release-report");
            return Boolean.TRUE.equals(r.get("ready")) ? GateResult.pass("release-checklist")
                    : GateResult.fail("release-checklist", "checklist has failing items");
        };
        Approval clarify = Approval.when(Approval.Timing.BEFORE_EXECUTION, bb -> {
            List<?> amb = ambiguities(bb.latest("requirements").map(v -> v.value()).orElse(Map.of()));
            return amb.isEmpty() ? Optional.empty() : Optional.of(amb.size() + " ambiguity(ies) need human input");
        }, bb -> {
            List<String> qs = new ArrayList<>();
            for (Object o : ambiguities(bb.latest("requirements").map(v -> v.value()).orElse(Map.of()))) {
                Map<String, Object> a = (Map<String, Object>) o;
                qs.add(a.get("id") + ": " + a.get("question") + " [proposed default: " + a.get("assumption") + "]");
            }
            return qs;
        });

        return new Plan().externalInputs("request")
                .add(TaskSpec.builder("analyze", Stage.REQUIREMENTS).agent("analyst").reads("request")
                        .writes("requirements").entryGate(Gate.requireArtifacts("request"))
                        .exitGate(requirementsOk).attempts(2, 0).build())
                .add(TaskSpec.builder("clarify", Stage.REQUIREMENTS).agent("clarifier").dependsOn("analyze")
                        .reads("requirements", "human.clarify").writes("decisions").approval(clarify)
                        .attempts(1, 0).build())
                .add(TaskSpec.builder("design", Stage.DESIGN).agent("architect").dependsOn("clarify")
                        .reads("requirements", "decisions").writes("design").capabilities(Capability.READ_WORKSPACE)
                        .exitGate(Gate.outputHas("design")).attempts(1, 0).build())
                .add(TaskSpec.builder("implement", Stage.IMPLEMENTATION).agent("draft-agent").fallback("recipe-agent")
                        .dependsOn("design").reads("design").writes("change").capabilities(Capability.WRITE_WORKSPACE)
                        .locks("workspace").entryGate(Gate.requireArtifacts("design")).exitGate(implementOk)
                        .attempts(1, 1).build())
                .add(TaskSpec.builder("test", Stage.TESTING).agent("tester").dependsOn("implement").reads("change")
                        .writes("test-report").capabilities(Capability.READ_WORKSPACE).locks("workspace")
                        .exitGate(testsGreen).attempts(1, 0).rollsBackAncestorsOnFailure(true).build())
                .add(TaskSpec.builder("docs", Stage.DOCUMENTATION).agent("writer").dependsOn("implement")
                        .reads("requirements", "decisions", "change").writes("docs").exitGate(docsTraceable)
                        .attempts(2, 0).build())
                .add(TaskSpec.builder("release", Stage.RELEASE_READINESS).agent("release-manager")
                        .dependsOn("test", "docs").reads("test-report", "design", "decisions", "docs", "change")
                        .writes("release-report").exitGate(releaseReady)
                        .approval(Approval.always(Approval.Timing.AFTER_EXECUTION, "release sign-off by a human owner"))
                        .attempts(1, 0).build());
    }

    @SuppressWarnings("unchecked")
    private static List<?> ambiguities(Object requirements) {
        Object a = ((Map<String, Object>) requirements).get("ambiguities");
        return a == null ? List.of() : (List<?>) a;
    }
}
