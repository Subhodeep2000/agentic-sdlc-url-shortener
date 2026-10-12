package com.acme.sdlc;

import com.acme.orchestrator.Approval;
import com.acme.orchestrator.AgentRegistry;
import com.acme.orchestrator.AuditLog;
import com.acme.orchestrator.Engine;
import com.acme.orchestrator.EngineConfig;
import com.acme.orchestrator.RunHook;
import com.acme.orchestrator.RunResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The three assignment scenarios, run end to end through the orchestrator against a real copy of the shortener source.
 * Usage: {@code java -cp out com.acme.sdlc.Scenarios [greenfield|brownfield|ambiguous|all] [outputDir]}.
 */
public final class Scenarios {
    public record Scenario(String name, String title, String kind, String request) {
    }

    public static final Scenario GREENFIELD = new Scenario("greenfield", "CSV export of link analytics", "greenfield",
            "Add a CSV export of link click statistics so analysts can download clicks per day and per referrer host. "
                    + "Cells that start with =, +, - or @ must be escaped to prevent spreadsheet formula injection.");
    public static final Scenario BROWNFIELD = new Scenario("brownfield", "Rate limiting behind a load balancer", "brownfield",
            "We are deploying behind our load balancer. The rate limiter must apply to each real client IP, not the "
                    + "proxy address. Do not let clients fake their address.");
    public static final Scenario AMBIGUOUS = new Scenario("ambiguous", "Smarter, safer analytics for teams", "ambiguous",
            "Make link analytics smarter and safer for our teams.\n"
                    + "Retain the click data as long as needed.\n"
                    + "The privacy team wants to know how links are doing, etc.");

    private Scenarios() {
    }

    public static void main(String[] args) throws Exception {
        String which = args.length > 0 ? args[0] : "all";
        Path out = Path.of(args.length > 1 ? args[1] : "runs");
        List<Scenario> list = new ArrayList<>();
        if (which.equals("all") || which.equals("greenfield")) {
            list.add(GREENFIELD);
        }
        if (which.equals("all") || which.equals("brownfield")) {
            list.add(BROWNFIELD);
        }
        if (which.equals("all") || which.equals("ambiguous")) {
            list.add(AMBIGUOUS);
        }
        if (list.isEmpty()) {
            System.err.println("unknown scenario '" + which + "'");
            System.exit(2);
        }
        for (Scenario s : list) {
            RunResult r = run(s, Path.of("."), out.resolve(s.name()));
            System.out.printf("%n=== %s (%s) -> %s%n", s.name(), s.title(), r.status());
            r.plan().tasks().forEach(t -> System.out.printf("  %-10s %-12s %s%n", t.id(), r.taskStatuses().get(t.id()),
                    r.taskNotes().getOrDefault(t.id(), "")));
            System.out.printf("  successRate=%.2f retries=%d fallbacks=%d rollbacks=%d replans=%d e2e=%dms%n",
                    r.metricDouble("successRate"), r.metricLong("retries"), r.metricLong("fallbacksUsed"),
                    r.metricLong("rollbackActions"), r.metricLong("replans"), r.metricLong("endToEndMillis"));
            System.out.println("  artifacts: " + out.resolve(s.name()).toAbsolutePath().normalize());
        }
    }

    /** Runs one scenario; writes audit.jsonl, summary.md and change.diff under {@code outDir}. */
    public static RunResult run(Scenario s, Path repo, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        Path wsRoot = outDir.resolve("workspace");
        if (Files.exists(wsRoot)) {
            try (var walk = Files.walk(wsRoot)) {
                for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.delete(p);
                }
            }
        }
        Path catalog = repo.resolve("catalog").toAbsolutePath().normalize();
        Workspace ws = Workspace.seed(wsRoot, repo.toAbsolutePath().normalize(), List.of("src/main/java/com/acme/common",
                "src/main/java/com/acme/shortener", "src/test/java/com/acme/testkit", "src/test/java/com/acme/shortener"));
        // The shipped repo may already contain earlier recipe output; scenarios start from the pristine baseline only.
        Path audit = outDir.resolve("audit.jsonl");
        EngineConfig cfg = EngineConfig.defaults().parallelism(4).deadline(Duration.ofMinutes(8)).auditFile(audit)
                .sleeper(ms -> { }).retryBackoff(0, 0)
                .resource(SdlcAgents.WORKSPACE, ws).resource(SdlcAgents.PATCHES, new PatchApplier(catalog))
                .resource(SdlcAgents.CATALOG_ROOT, catalog).resource(SdlcAgents.BUILD, new BuildRunner(180));
        AgentRegistry agents = new AgentRegistry().register(SdlcAgents.analyst()).register(SdlcAgents.clarifier())
                .register(SdlcAgents.architect()).register(SdlcAgents.patchAgent("draft-agent", true))
                .register(SdlcAgents.patchAgent("recipe-agent", false)).register(SdlcAgents.tester())
                .register(SdlcAgents.writer()).register(SdlcAgents.releaseManager());

        boolean ambiguous = s.kind().equals("ambiguous");
        Approval.Handler human = req -> {
            System.out.printf("  [human] %s asks (%s): %s%n", req.taskId(), req.timing(), req.reason());
            req.questions().forEach(q -> System.out.println("          ? " + q));
            if (req.taskId().equals("clarify")) {
                return Approval.Decision.approve("product-owner", "answered the open questions",
                        Map.of("retention_days", 30L));
            }
            return Approval.Decision.approve("release-owner", "reviewed the readiness report and evidence hashes");
        };
        AtomicInteger fired = new AtomicInteger();
        RunHook hook = new RunHook() {
            @Override
            public void onTaskSucceeded(String taskId, Controls controls) {
                // Ambiguous scenario: the product owner revises the retention answer after tests went green.
                if (ambiguous && taskId.equals("test") && fired.getAndIncrement() == 0) {
                    System.out.println("  [human] product-owner revises retention_days 30 -> 14 after tests passed");
                    controls.submitChange("human.clarify", Map.of("retention_days", 14L), "product-owner");
                }
            }
        };
        RunResult r = new Engine(cfg, agents, human).run(SdlcPlan.build(), Map.of("request", s.request()), hook);
        boolean fileOk = AuditLog.verifyFile(audit);
        Files.writeString(outDir.resolve("summary.md"), SummaryWriter.render(s.title(), s.kind(), s.request(), r, fileOk),
                StandardCharsets.UTF_8);
        r.blackboard().latest("change").ifPresent(c -> {
            try {
                @SuppressWarnings("unchecked")
                Object diff = ((Map<String, Object>) c.value()).get("diff");
                Files.writeString(outDir.resolve("change.diff"), String.valueOf(diff), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        return r;
    }
}
