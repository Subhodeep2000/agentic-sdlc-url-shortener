package com.acme.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Scans every string an agent outputs for credential material. A hit is CRITICAL: a leaked secret is not something
 * to retry past, so it safe-stops the whole run for human review.
 */
public final class SecretScanPolicy implements Policy {
    private static final List<Pattern> PATTERNS = List.of(Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("(?i)(password|passwd|secret|api[_-]?key|token)\\s*[=:]\\s*[\"'][^\"'\\s]{6,}[\"']"),
            Pattern.compile("sk-[A-Za-z0-9]{20,}"));

    @Override
    public String id() {
        return "SEC-1 secret-scan";
    }

    @Override
    public PolicyCategory category() {
        return PolicyCategory.SECURITY;
    }

    @Override
    public List<Violation> evaluate(Context ctx) {
        List<Violation> out = new ArrayList<>();
        if (ctx.phase() == Phase.POST_TASK && ctx.output() != null) {
            scan(ctx.output().artifacts(), "", out);
        }
        return out;
    }

    private void scan(Object v, String path, List<Violation> out) {
        if (v instanceof String s) {
            for (Pattern p : PATTERNS) {
                if (p.matcher(s).find()) {
                    out.add(new Violation(id(), category(), Severity.CRITICAL,
                            "possible credential in " + (path.isEmpty() ? "output" : path)));
                    return;
                }
            }
        } else if (v instanceof Map<?, ?> m) {
            m.forEach((k, x) -> scan(x, path + "/" + k, out));
        } else if (v instanceof Iterable<?> it) {
            int i = 0;
            for (Object e : it) {
                scan(e, path + "[" + i++ + "]", out);
            }
        }
    }
}
