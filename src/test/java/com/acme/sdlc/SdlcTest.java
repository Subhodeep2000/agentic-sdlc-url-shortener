package com.acme.sdlc;

import static com.acme.testkit.Assert.assertEquals;
import static com.acme.testkit.Assert.assertFalse;
import static com.acme.testkit.Assert.assertThrows;
import static com.acme.testkit.Assert.assertTrue;

import com.acme.orchestrator.AgentException;
import com.acme.orchestrator.RunResult;
import com.acme.orchestrator.RunStatus;
import com.acme.orchestrator.TaskStatus;
import com.acme.testkit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tests of the SDLC layer: analyzers, workspace safety, patching, and the three scenarios end to end. */
public class SdlcTest {
    private static final Path REPO = Path.of(".").toAbsolutePath().normalize();

    private static Workspace seeded() throws Exception {
        return Workspace.seed(Files.createTempDirectory("ws"), REPO, List.of("src/main/java/com/acme/common",
                "src/main/java/com/acme/shortener", "src/test/java/com/acme/testkit", "src/test/java/com/acme/shortener"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void requirementsAnalyzerFindsAmbiguityAndStaysQuietWhenClear() {
        Map<String, Object> clear = new RequirementsAnalyzer().analyze(Scenarios.GREENFIELD.request());
        assertEquals(0, ((List<?>) clear.get("ambiguities")).size());
        assertTrue(((List<String>) clear.get("concepts")).contains("export"), "export concept detected");

        Map<String, Object> vague = new RequirementsAnalyzer().analyze(Scenarios.AMBIGUOUS.request());
        List<Map<String, Object>> amb = (List<Map<String, Object>>) vague.get("ambiguities");
        Set<String> kinds = new java.util.HashSet<>();
        amb.forEach(a -> kinds.add((String) a.get("kind")));
        assertTrue(kinds.containsAll(Set.of("VAGUE_TERM", "UNDEFINED_ACTOR", "MISSING_PARAMETER", "OPEN_ENDED")), kinds.toString());
        assertTrue(amb.stream().anyMatch(a -> "retention_days".equals(a.get("parameter"))), "retention parameter asked for");

        Map<String, Object> conflict = new RequirementsAnalyzer().analyze(
                "We must track unique visitors per user. Privacy and anonymity are required.");
        assertTrue(((List<Map<String, Object>>) conflict.get("ambiguities")).stream()
                .anyMatch(a -> "CONFLICT".equals(a.get("kind"))), "tracking vs privacy conflict flagged");
        assertThrows(IllegalArgumentException.class, () -> new RequirementsAnalyzer().analyze("  "));
    }

    @Test
    public void codebaseAnalyzerComputesImpactAndFlows() throws Exception {
        CodebaseAnalyzer code = CodebaseAnalyzer.scan(seeded());
        Set<String> seeds = code.classesForConcepts(List.of("client-ip"));
        assertEquals(Set.of("RateLimiter", "ShortenerServer"), seeds);
        Map<String, String> impact = code.impactOf(seeds);
        assertTrue(impact.containsKey("ShortenerServer") && impact.containsKey("ShortenerApp"), impact.toString());
        assertFalse(impact.containsKey("UrlValidator"), "unrelated class is not impacted");
        assertTrue(code.testsFor(impact.keySet()).contains("ShortenerTest"));
        assertTrue(code.flowsTo(seeds, impact.keySet()).contains("ShortenerServer -> RateLimiter"));
        assertTrue(code.endpoints().stream().anyMatch(e -> e.path().equals("/api/v1/links") && e.method().equals("POST")));
    }

    @Test
    public void workspaceConfinesPathsAndRestoresExactly() throws Exception {
        Workspace ws = seeded();
        assertThrows(IllegalArgumentException.class, () -> ws.resolve("../escape.txt"));
        String before = ws.treeHash();
        ws.checkpoint("c");
        ws.write("src/main/java/com/acme/shortener/NewThing.java", "class NewThing {}");
        ws.write("src/main/java/com/acme/shortener/Link.java", "changed");
        assertFalse(before.equals(ws.treeHash()));
        ws.restore("c");
        assertEquals(before, ws.treeHash());
    }

    @Test
    public void patchApplierIsAllOrNothingAndFailsOnDrift() throws Exception {
        PatchApplier applier = new PatchApplier(REPO.resolve("catalog"));
        Workspace ws = seeded();
        String before = ws.treeHash();
        AgentException missing = assertThrows(AgentException.class, () -> applier.apply(ws, "analytics-retention", Map.of()));
        assertTrue(missing.getMessage().contains("retention_days"), missing.getMessage());
        assertEquals(before, ws.treeHash(), "a failed patch leaves the workspace untouched");

        ws.write("src/main/java/com/acme/shortener/ShortenerServer.java", "// drifted\n");
        AgentException drift = assertThrows(AgentException.class, () -> applier.apply(ws, "trusted-proxy", Map.of()));
        assertTrue(drift.getMessage().contains("drifted"), drift.getMessage());
        assertFalse(drift.retryable(), "drift is permanent, never retried");
        assertThrows(AgentException.class, () -> applier.apply(ws, "../../etc", Map.of()));
    }

    @Test
    public void diffShowsChanges() {
        String d = SimpleDiff.unified("a.txt", "one\ntwo\nthree\n", "one\n2\nthree\n");
        assertTrue(d.contains("-two") && d.contains("+2"), d);
        assertEquals(1, SimpleDiff.stats("a\nb\n", "a\nc\n").added());
    }

    @Test
    public void buildRunnerReportsCompileErrors() throws Exception {
        Workspace ws = seeded();
        ws.write("src/main/java/com/acme/shortener/Broken.java", "package com.acme.shortener; class Broken { int x = ; }");
        assertFalse(new BuildRunner(120).compile(ws).ok(), "syntax error must fail the build");
    }

    @Test
    public void brownfieldScenarioCatchesBadDraftRollsBackAndFallsBack() throws Exception {
        RunResult r = Scenarios.run(Scenarios.BROWNFIELD, REPO, Files.createTempDirectory("bf"));
        assertEquals(RunStatus.COMPLETED, r.status(), r.taskNotes().toString());
        assertEquals(1L, r.metricLong("fallbacksUsed"));
        assertEquals(1L, r.metricLong("rollbackActions"));
        assertTrue(r.audit().events().stream().anyMatch(e -> e.type().equals("ATTEMPT_FAILED")
                && String.valueOf(e.details()).contains("TrustedProxyTest")), "draft failed its own security test");
        assertEquals("trusted-proxy", ((Map<?, ?>) r.blackboard().latest("change").get().value()).get("patch"));
        assertTrue(r.audit().verify());
    }

    @Test
    public void greenfieldScenarioDeliversFeatureWithTestsAndParallelJoin() throws Exception {
        RunResult r = Scenarios.run(Scenarios.GREENFIELD, REPO, Files.createTempDirectory("gf"));
        assertEquals(RunStatus.COMPLETED, r.status(), r.taskNotes().toString());
        assertTrue(r.completionOrder().indexOf("release") > r.completionOrder().indexOf("test")
                && r.completionOrder().indexOf("release") > r.completionOrder().indexOf("docs"), "join at release");
        assertTrue(((List<?>) ((Map<?, ?>) r.blackboard().latest("change").get().value()).get("newTests"))
                .contains("StatsCsvTest"));
    }

    @Test
    public void ambiguousScenarioAsksHumanThenReplansOnRevisedAnswer() throws Exception {
        RunResult r = Scenarios.run(Scenarios.AMBIGUOUS, REPO, Files.createTempDirectory("amb"));
        assertEquals(RunStatus.COMPLETED, r.status(), r.taskNotes().toString());
        assertEquals(1L, r.metricLong("replans"));
        assertTrue(r.metricLong("approvals.reused") >= 1, "clarification approval reused when evidence is unchanged");
        assertEquals(2, r.blackboard().latestVersion("human.clarify"));
        assertEquals("14", ((Map<?, ?>) ((Map<?, ?>) r.blackboard().latest("decisions").get().value())
                .get("parameters")).get("retention_days"));
        assertTrue(String.valueOf(r.blackboard().latest("change").get().value()).contains("purgeAnalyticsOlderThan(14)"));
        assertEquals(TaskStatus.SUCCEEDED, r.taskStatuses().get("release"));
    }
}
