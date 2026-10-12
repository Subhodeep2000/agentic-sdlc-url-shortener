package com.acme.sdlc;

import com.acme.common.Json;
import com.acme.orchestrator.AuditEvent;
import com.acme.orchestrator.RunResult;
import com.acme.orchestrator.TaskSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Renders the final engineering summary (plan/rationale, artifacts, risks/validation, assumptions, limitations). */
public final class SummaryWriter {
    private SummaryWriter() {
    }

    @SuppressWarnings("unchecked")
    public static String render(String title, String kind, String request, RunResult r, boolean fileAuditVerified) {
        StringBuilder md = new StringBuilder();
        md.append("# Final engineering summary: ").append(title).append("\n\n");
        md.append("Scenario type: **").append(kind).append("** | Run: `").append(r.runId()).append("` | Outcome: **")
                .append(r.status()).append("**").append(r.stopReason() == null ? "" : " (" + r.stopReason() + ")")
                .append("\n\n## 1. Request (verbatim)\n\n> ").append(request.replace("\n", "\n> ")).append("\n\n");

        Map<String, Object> req = value(r, "requirements");
        Map<String, Object> decisions = value(r, "decisions");
        Map<String, Object> design = value(r, "design");
        Map<String, Object> change = value(r, "change");
        Map<String, Object> tests = value(r, "test-report");

        md.append("## 2. Requirement understanding\n\n");
        if (req != null) {
            md.append(req.get("statement")).append("\n\n| Id | Priority | Requirement | Acceptance criteria |\n|---|---|---|---|\n");
            for (Object o : (List<Object>) req.get("items")) {
                Map<String, Object> it = (Map<String, Object>) o;
                md.append("| ").append(it.get("id")).append(" | ").append(it.get("priority")).append(" | ")
                        .append(it.get("text")).append(" | ").append(String.join("<br>", (List<String>) it.get("acceptance")))
                        .append(" |\n");
            }
            List<Object> amb = (List<Object>) req.get("ambiguities");
            md.append("\n**Ambiguities found: ").append(amb.size()).append("**\n\n");
            for (Object o : amb) {
                Map<String, Object> a = (Map<String, Object>) o;
                md.append("- ").append(a.get("id")).append(" `").append(a.get("kind")).append("` ").append(a.get("why"))
                        .append(". Question: ").append(a.get("question")).append(" Proposed default: ")
                        .append(a.get("assumption")).append("\n");
            }
            if (decisions != null) {
                md.append("\n**Resolution** (human answers: ").append(Json.write(decisions.get("humanInputs")))
                        .append("):\n\n");
                ((List<Object>) decisions.get("assumptions")).forEach(a -> md.append("- ").append(a).append("\n"));
            }
        } else {
            md.append("Not reached.\n");
        }

        md.append("\n## 3. Plan and task decomposition (execution result)\n\n| Task | Stage | Depends on | Status | Note |\n|---|---|---|---|---|\n");
        for (TaskSpec t : r.plan().tasks()) {
            md.append("| ").append(t.id()).append(" | ").append(t.stage()).append(" | ")
                    .append(t.dependsOn().isEmpty() ? "-" : String.join(", ", t.dependsOn())).append(" | ")
                    .append(r.taskStatuses().get(t.id())).append(" | ")
                    .append(String.valueOf(r.taskNotes().getOrDefault(t.id(), "")).replace("|", "/")).append(" |\n");
        }
        md.append("\nCompletion order (shows the parallel test/docs branches joining at release): ")
                .append(r.completionOrder()).append("\n");

        md.append("\n## 4. Rationale, impact and trade-offs\n\n");
        if (design != null) {
            md.append("- Recipe: `").append(design.get("patch")).append("` - ").append(design.get("patchSummary")).append("\n");
            md.append("- Impacted classes (reverse-dependency closure): ").append(design.get("impact")).append("\n");
            md.append("- Request flows reaching the change: ").append(design.get("flows")).append("\n");
            md.append("- Regression scope: ").append(design.get("testsImpacted")).append(" plus new tests\n");
            md.append("- Parameters: ").append(design.get("params")).append("\n\nTrade-offs:\n\n");
            ((List<Object>) design.get("tradeoffs")).forEach(t -> md.append("- ").append(t).append("\n"));
        }

        md.append("\n## 5. Artifacts produced\n\n| Artifact | Versions | Latest hash | Producer |\n|---|---|---|---|\n");
        for (String k : r.blackboard().keys()) {
            var latest = r.blackboard().latest(k).orElseThrow();
            md.append("| ").append(k).append(" | ").append(r.blackboard().latestVersion(k)).append(" | `")
                    .append(latest.hash(), 0, 12).append("` | ").append(latest.producer()).append(" |\n");
        }
        if (change != null) {
            md.append("\nFiles changed:\n\n");
            for (Object o : (List<Object>) change.get("files")) {
                Map<String, Object> f = (Map<String, Object>) o;
                md.append("- `").append(f.get("path")).append("` ").append(f.get("op")).append(" (+").append(f.get("added"))
                        .append("/-").append(f.get("removed")).append(")\n");
            }
        }

        md.append("\n## 6. Validation and risk control\n\n");
        if (tests != null) {
            md.append("- Full regression: **").append(tests.get("passed")).append("/").append(tests.get("run"))
                    .append(" passed**, compiled from scratch in a child JVM.\n");
        }
        md.append("- Governance timeline (from the audit log):\n\n");
        Set<String> interesting = Set.of("ATTEMPT_FAILED", "FALLBACK_INVOKED", "ROLLBACK_DONE", "APPROVAL_DECIDED",
                "APPROVAL_REUSED", "REPLAN", "POLICY_VIOLATION", "EXTERNAL_CHANGE", "SAFE_STOP", "TASK_FAILED");
        for (AuditEvent e : r.audit().events()) {
            if (interesting.contains(e.type())) {
                md.append("  - #").append(e.seq()).append(" `").append(e.type()).append("` ")
                        .append(e.taskId() == null ? "" : e.taskId() + " ").append(shorten(Json.write(e.details()))).append("\n");
            }
        }
        if (req != null) {
            md.append("\nRisk register:\n\n| Risk | Severity | Mitigation in this change |\n|---|---|---|\n");
            for (Object o : (List<Object>) req.get("risks")) {
                Map<String, Object> k = (Map<String, Object>) o;
                md.append("| ").append(k.get("id")).append(": ").append(k.get("text")).append(" | ").append(k.get("severity"))
                        .append(" | ").append(mitigation((String) k.get("id"))).append(" |\n");
            }
        }

        md.append("\n## 7. Governance and observability\n\n| Metric | Value |\n|---|---|\n");
        r.metrics().forEach((k, v) -> md.append("| ").append(k).append(" | ")
                .append(v instanceof Double d ? (d.isNaN() ? "n/a" : String.format("%.3f", d)) : v).append(" |\n"));
        md.append("\n- Audit events: ").append(r.audit().events().size()).append("; hash chain verified in memory: **")
                .append(r.audit().verify()).append("**; on-disk JSONL verified: **").append(fileAuditVerified).append("**\n");
        var rel = r.blackboard().latest("release-report");
        if (rel.isPresent()) {
            md.append("\nDecision lineage of the release report:\n\n```\n")
                    .append(String.join("\n", r.lineage().trace(rel.get().ref()))).append("\n```\n");
        }

        md.append("\n## 8. Assumptions and limitations\n\n")
                .append("- Agents are deterministic, catalog-driven stand-ins for LLM agents. They obey the same contracts (declared inputs/outputs, gates, policies, approvals), so an LLM-backed agent can replace one without changing the governance.\n")
                .append("- Requirement analysis is lexicon/pattern based and conservative; it asks when unsure but can miss domain ambiguity it has no vocabulary for.\n")
                .append("- The implementer applies reviewed recipes; it cannot invent code for a requirement outside the catalog (it fails closed instead).\n")
                .append("- Human approvers are scripted in these demos; in production the approval handler is a ticket/chat-ops integration with real identities.\n")
                .append("- Builds run in child JVMs with a timeout and stripped secrets: process isolation, not a security sandbox.\n")
                .append("- The audit hash chain proves internal consistency, not authenticity: sign or externally anchor the head hash in production.\n")
                .append("- State is in memory: a crashed run is not resumable. The service itself is single-node and in-memory (links are lost on restart).\n");
        return md.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> value(RunResult r, String key) {
        return r.blackboard().latest(key).map(v -> (Map<String, Object>) v.value()).orElse(null);
    }

    private static String shorten(String s) {
        return s.length() > 220 ? s.substring(0, 220) + "..." : s;
    }

    private static String mitigation(String id) {
        return switch (id) {
            case "SPOOFING" -> "X-Forwarded-For honoured only from configured trusted proxies; right-most untrusted hop; spoof tests";
            case "CSV_INJECTION" -> "formula-trigger cells are quote-prefixed; unit tests";
            case "SSRF_OPEN_REDIRECT" -> "existing UrlValidator (scheme, credentials, internal hosts), unchanged by this work";
            case "ABUSE_DOS" -> "existing per-client token buckets, bounded key table";
            case "PRIVACY_RETENTION" -> "aggregate-only analytics; per-day buckets purged after the retention period where applicable";
            default -> "see design";
        };
    }
}
