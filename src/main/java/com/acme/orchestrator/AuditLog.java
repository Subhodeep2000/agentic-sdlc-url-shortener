package com.acme.orchestrator;

import com.acme.common.Hashing;
import com.acme.common.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only, hash-chained audit log (each event commits to its predecessor), optionally mirrored to JSONL.
 * {@link #verify} detects edits, deletions and reordering. A hash chain proves internal consistency only; production
 * should also sign or externally anchor the head hash.
 */
public final class AuditLog {
    static final String GENESIS = "0".repeat(64);
    private final List<AuditEvent> events = new ArrayList<>();
    private final Clock clock;
    private final String runId;
    private final Path sink;

    public AuditLog(String runId, Clock clock, Path sinkOrNull) {
        this.runId = runId;
        this.clock = clock;
        this.sink = sinkOrNull;
        if (sink != null) {
            try {
                Files.createDirectories(sink.toAbsolutePath().getParent());
                Files.writeString(sink, "", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public synchronized AuditEvent append(String type, String taskId, String actor, Map<String, Object> details) {
        String prev = events.isEmpty() ? GENESIS : events.get(events.size() - 1).hash();
        long seq = events.size() + 1L;
        String ts = clock.instant().toString();
        Map<String, Object> d = details == null ? Map.of() : details;
        AuditEvent e = new AuditEvent(seq, ts, runId, type, taskId, actor, d, prev,
                digest(prev, seq, ts, runId, type, taskId, actor, d));
        events.add(e);
        if (sink != null) {
            try {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("seq", e.seq());
                m.put("timestamp", ts);
                m.put("runId", runId);
                m.put("type", type);
                m.put("taskId", taskId);
                m.put("actor", actor);
                m.put("details", d);
                m.put("prevHash", prev);
                m.put("hash", e.hash());
                Files.writeString(sink, Json.write(m) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return e;
    }

    public synchronized List<AuditEvent> events() {
        return List.copyOf(events);
    }

    public synchronized boolean verify() {
        return verify(events);
    }

    public static boolean verify(List<AuditEvent> list) {
        String prev = GENESIS;
        long seq = 1;
        for (AuditEvent e : list) {
            if (e.seq() != seq++ || !e.prevHash().equals(prev)
                    || !e.hash().equals(digest(prev, e.seq(), e.timestamp(), e.runId(), e.type(), e.taskId(),
                    e.actor(), e.details()))) {
                return false;
            }
            prev = e.hash();
        }
        return true;
    }

    /** Re-reads a JSONL audit file and verifies its chain. */
    @SuppressWarnings("unchecked")
    public static boolean verifyFile(Path file) throws IOException {
        List<AuditEvent> list = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> m = Json.parseObject(line);
            list.add(new AuditEvent((Long) m.get("seq"), (String) m.get("timestamp"), (String) m.get("runId"),
                    (String) m.get("type"), (String) m.get("taskId"), (String) m.get("actor"),
                    (Map<String, Object>) m.get("details"), (String) m.get("prevHash"), (String) m.get("hash")));
        }
        return verify(list);
    }

    private static String digest(String prev, long seq, String ts, String runId, String type, String taskId,
                                 String actor, Map<String, Object> d) {
        return Hashing.sha256(String.join("|", prev, String.valueOf(seq), ts, String.valueOf(runId), type,
                String.valueOf(taskId), String.valueOf(actor), Json.write(d)));
    }
}
