package com.acme.sdlc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Compiles and tests a workspace in child JVMs. Guardrails for running agent-produced code: hard timeout, capped heap,
 * output size cap, working directory pinned to the workspace, secret-looking environment variables stripped.
 * This is process isolation, not a security sandbox; production should build in a locked-down, network-less container.
 */
public final class BuildRunner {
    public record Result(boolean ok, int exitCode, String output, long millis) {
    }

    public record TestReport(int run, int passed, int failed, List<String> passedTests, List<String> failedTests,
                             boolean ok, String tail) {
    }

    private static final int OUTPUT_CAP = 200_000;
    private final long timeoutSeconds;

    public BuildRunner(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Result compile(Workspace ws) {
        List<String> files = ws.javaFiles("src/");
        if (files.isEmpty()) {
            return new Result(false, -1, "no java sources in workspace", 0);
        }
        try {
            Files.createDirectories(ws.outDir());
        } catch (IOException e) {
            return new Result(false, -1, "cannot create out dir: " + e, 0);
        }
        List<String> cmd = new ArrayList<>(List.of(tool("javac"), "--release", "21", "-nowarn", "-d", "out"));
        cmd.addAll(files);
        return exec(ws.root(), cmd);
    }

    /** Runs test classes whose simple name matches {@code classRegex}; an empty selection counts as failure. */
    public TestReport test(Workspace ws, String classRegex) {
        if (!Files.isDirectory(ws.outDir())) {
            return new TestReport(0, 0, 0, List.of(), List.of(), false, "workspace is not compiled");
        }
        Result r = exec(ws.root(), List.of(tool("java"), "-Xmx256m", "-cp", "out", "com.acme.testkit.TestRunner", "out",
                "", classRegex));
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String line : r.output().split("\n")) {
            if (line.startsWith("PASS  ")) {
                passed.add(line.substring(6).trim());
            } else if (line.startsWith("FAIL  ")) {
                failed.add(line.substring(6).trim());
            }
        }
        String out = r.output();
        return new TestReport(passed.size() + failed.size(), passed.size(), failed.size(), passed, failed,
                r.ok() && failed.isEmpty() && !passed.isEmpty(), out.length() > 1500 ? out.substring(out.length() - 1500) : out);
    }

    private Result exec(Path dir, List<String> cmd) {
        long t0 = System.currentTimeMillis();
        Path log = null;
        try {
            log = Files.createTempFile("build-", ".log");
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            pb.environment().keySet().removeIf(k -> k.matches("(?i).*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL).*"));
            Process p = pb.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(false, -1, "timed out after " + timeoutSeconds + "s", System.currentTimeMillis() - t0);
            }
            byte[] bytes = Files.readAllBytes(log);
            return new Result(p.exitValue() == 0, p.exitValue(),
                    new String(bytes, 0, Math.min(bytes.length, OUTPUT_CAP), StandardCharsets.UTF_8),
                    System.currentTimeMillis() - t0);
        } catch (IOException e) {
            return new Result(false, -1, "process error: " + e, System.currentTimeMillis() - t0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, -1, "interrupted", System.currentTimeMillis() - t0);
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static String tool(String name) {
        Path p = Path.of(System.getProperty("java.home"), "bin", name);
        return Files.isExecutable(p) ? p.toString() : name;
    }
}
