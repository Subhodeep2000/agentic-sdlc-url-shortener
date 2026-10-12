package com.acme.sdlc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic requirement understanding: splits free text into atomic requirement items, tags them with domain
 * concepts, derives acceptance criteria, and surfaces ambiguity (vague terms, missing parameters, undefined actors,
 * open-ended lists, conflicts) as explicit questions with a proposed default.
 *
 * <p>A lexicon/pattern baseline, deliberately conservative: when unsure it asks. An LLM can replace it behind the same
 * artifact contract, but its output would still pass through the same gates, policies and approvals.
 */
public final class RequirementsAnalyzer {
    static final Set<String> FUNCTIONAL = Set.of("create", "redirect", "alias", "expiry", "analytics", "rate-limit",
            "proxy", "client-ip", "export");

    private static final Map<String, List<String>> LEXICON = new LinkedHashMap<>();
    private static final Map<String, List<String>> ACCEPTANCE = new LinkedHashMap<>();

    static {
        LEXICON.put("create", List.of("shorten", "short link", "short url", "create a short"));
        LEXICON.put("redirect", List.of("redirect", "resolve", "expand"));
        LEXICON.put("alias", List.of("alias", "custom code", "vanity"));
        LEXICON.put("expiry", List.of("expir", "ttl", "lifetime", "valid for"));
        LEXICON.put("analytics", List.of("analytic", "click", "stats", "statistic", "metric", "links are doing", "referrer"));
        LEXICON.put("export", List.of("export", "csv", "download"));
        LEXICON.put("rate-limit", List.of("rate limit", "rate-limit", "throttl", "abuse", "429"));
        LEXICON.put("security", List.of("secur", "safe", "malicious", "ssrf", "spoof", "fake", "injection"));
        LEXICON.put("privacy", List.of("privacy", "personal data", "pii", "gdpr", "anonym", "retention", "retain"));
        LEXICON.put("reliability", List.of("reliab", "availab", "uptime", "resilien"));
        LEXICON.put("performance", List.of("fast", "latency", "throughput", "scal", "speed"));
        LEXICON.put("proxy", List.of("load balancer", "proxy", "x-forwarded", "behind our"));
        LEXICON.put("client-ip", List.of("client ip", "real client", "each client", "per client"));
        LEXICON.put("auth", List.of("authenticat", "authoriz", "login", "sso", "oauth", "api key", "permission"));

        ACCEPTANCE.put("create", List.of("POST /api/v1/links with a valid http(s) URL returns 201 with code and shortUrl",
                "an invalid or over-long URL returns 400"));
        ACCEPTANCE.put("redirect", List.of("GET /{code} returns 302 with Location equal to the original URL",
                "unknown code returns 404; expired link returns 410"));
        ACCEPTANCE.put("analytics", List.of("GET /api/v1/links/{code}/stats returns total, per-day and referrer-host counts",
                "no IP address, user agent or full referrer URL is stored"));
        ACCEPTANCE.put("export", List.of("GET /api/v1/links/{code}/stats.csv returns text/csv with total, per-day and referrer rows",
                "cells beginning with = + - @ are neutralised so spreadsheets cannot evaluate them"));
        ACCEPTANCE.put("rate-limit", List.of("requests above the limit receive 429 with a Retry-After header",
                "limits are tracked per client key, independently"));
        ACCEPTANCE.put("security", List.of("hostile input (schemes, internal hosts, forged headers) is rejected or ignored"));
        ACCEPTANCE.put("privacy", List.of("only aggregate counters are retained; no personal data is persisted",
                "per-day analytics older than the retention period are purged"));
        ACCEPTANCE.put("proxy", List.of("X-Forwarded-For is honoured only when the direct peer is a configured trusted proxy",
                "a client-supplied X-Forwarded-For from an untrusted peer is ignored"));
        ACCEPTANCE.put("client-ip", List.of("two clients behind the same trusted proxy are rate limited separately",
                "direct connections behave exactly as before"));
        ACCEPTANCE.put("reliability", List.of("failures degrade to a defined error response, never an unhandled 500 with internals"));
        ACCEPTANCE.put("performance", List.of("redirect and stats p95 latency stays within the agreed budget on a single node"));
    }

    private static final Pattern VAGUE = Pattern.compile("\\b(smarter|smart|safer|safe|secure|faster|fast|quickly|"
            + "scalable|robust|reliable|easy|easier|simple|better|improved?|good|nice|flexible|soon|enough|"
            + "appropriate|reasonable|intuitive|efficient)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTOR = Pattern.compile(
            "\\b(teams?|admins?|stakeholders?|customers?|managers?|privacy team|support staff)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPEN_ENDED = Pattern.compile("\\b(etc\\.?|and so on|and more|as needed|among others)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RETENTION_UNBOUNDED = Pattern.compile(
            "\\b(forever|indefinite(ly)?|long[- ]term|as long as)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION = Pattern.compile("\\d+\\s*(day|week|month|year)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACKING = Pattern.compile(
            "\\b(unique visitors?|per[- ]user|fingerprint|who clicked|individual users?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVACY_WORDS = Pattern.compile("\\b(privacy|personal data|gdpr|anonym\\w*)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> VAGUE_DEFAULTS = Map.ofEntries(
            Map.entry("smarter", "No new behaviour: keep total, per-day and referrer-host analytics"),
            Map.entry("smart", "No new behaviour: keep total, per-day and referrer-host analytics"),
            Map.entry("safe", "No personal data stored; existing URL validation and rate limiting"),
            Map.entry("safer", "No personal data stored; existing URL validation and rate limiting"),
            Map.entry("secure", "OWASP-aligned input validation, no credentials in URLs, SSRF guard"),
            Map.entry("fast", "p95 latency below 200 ms for redirect and stats on a single node"),
            Map.entry("faster", "p95 latency below 200 ms for redirect and stats on a single node"),
            Map.entry("scalable", "Single node, in-memory; horizontal scale-out is out of scope"),
            Map.entry("reliable", "Bounded retries and graceful error responses; no availability SLO committed"),
            Map.entry("easy", "Plain REST/JSON API with documented errors; no UI in this iteration"));

    public Map<String, Object> analyze(String request) {
        if (request == null || request.isBlank()) {
            throw new IllegalArgumentException("request is empty");
        }
        String text = request.strip();
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>();
        int n = 0;
        for (String raw : text.split("(?<=[.!?;])\\s+|\\R+")) {
            String s = raw.strip().replaceFirst("^([-*•]|\\d+[.)])\\s*", "");
            if (s.length() < 8) {
                continue;
            }
            Set<String> concepts = conceptsOf(s);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "R" + (++n));
            item.put("type", concepts.isEmpty() || concepts.stream().anyMatch(FUNCTIONAL::contains)
                    ? "FUNCTIONAL" : "NON_FUNCTIONAL");
            item.put("priority", priority(s));
            item.put("text", s.replaceAll("[.;]$", ""));
            item.put("concepts", new ArrayList<>(concepts));
            List<String> acceptance = new ArrayList<>();
            concepts.forEach(c -> acceptance.addAll(ACCEPTANCE.getOrDefault(c, List.of())));
            item.put("acceptance", acceptance);
            items.add(item);
            all.addAll(concepts);
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("no requirements could be extracted from the request");
        }
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("securitySensitive", all.stream().anyMatch(c -> Set.of("rate-limit", "security", "auth", "proxy",
                "client-ip", "export").contains(c)));
        flags.put("privacySensitive", all.contains("privacy") || all.contains("analytics"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statement", "Engineering problem: deliver/modify URL-shortener behaviour for " + all
                + " with each requirement made testable by explicit acceptance criteria.");
        out.put("items", items);
        out.put("concepts", new ArrayList<>(all));
        out.put("ambiguities", ambiguities(text, items));
        out.put("risks", risks(all));
        out.put("flags", flags);
        return out;
    }

    static Set<String> conceptsOf(String sentence) {
        String lower = sentence.toLowerCase();
        Set<String> out = new LinkedHashSet<>();
        LEXICON.forEach((concept, kws) -> {
            for (String kw : kws) {
                if (Pattern.compile("\\b" + Pattern.quote(kw)).matcher(lower).find()) {
                    out.add(concept);
                    break;
                }
            }
        });
        return out;
    }

    private static String priority(String s) {
        String l = s.toLowerCase();
        if (l.matches(".*\\b(must|shall|required|need to|has to)\\b.*")) {
            return "MUST";
        }
        if (l.matches(".*\\b(could|optional|nice to have|may)\\b.*")) {
            return "COULD";
        }
        return "SHOULD";
    }

    private List<Map<String, Object>> ambiguities(String text, List<Map<String, Object>> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> it : items) {
            String sentence = (String) it.get("text");
            if (Pattern.compile("\\d").matcher(sentence).find()) {
                continue; // quantified sentences are treated as testable
            }
            Matcher m = VAGUE.matcher(sentence);
            while (m.find()) {
                String term = m.group(1).toLowerCase();
                if (seen.add(term)) {
                    out.add(amb(out.size() + 1, "VAGUE_TERM", term,
                            "'" + term + "' is subjective and cannot be tested as written (" + it.get("id") + ")",
                            "What does '" + term + "' mean concretely - which observable behaviour or number?",
                            VAGUE_DEFAULTS.getOrDefault(term, "Treat as non-binding; derive no requirement from it"),
                            "", ""));
                }
            }
            Matcher a = ACTOR.matcher(sentence);
            while (a.find()) {
                String term = a.group(1).toLowerCase();
                if (seen.add("actor:" + term)) {
                    out.add(amb(out.size() + 1, "UNDEFINED_ACTOR", term,
                            "'" + term + "' is an actor with no defined identity or access model (" + it.get("id") + ")",
                            "Who exactly are the '" + term + "', and must they authenticate to see analytics?",
                            "No authentication in this iteration; the stats API stays internal-only; access control is a follow-up",
                            "", ""));
                }
            }
        }
        if (RETENTION_UNBOUNDED.matcher(text).find() && !DURATION.matcher(text).find()) {
            out.add(amb(out.size() + 1, "MISSING_PARAMETER", "retention", "data retention is mentioned but no period is given",
                    "For how many days may per-day analytics be retained? (retention_days)",
                    "90 days", "retention_days", "90"));
        }
        if (OPEN_ENDED.matcher(text).find()) {
            out.add(amb(out.size() + 1, "OPEN_ENDED", "etc", "an open-ended list leaves scope undefined",
                    "Which items are in scope? Please enumerate them.", "Only the explicitly listed items are in scope",
                    "", ""));
        }
        if (TRACKING.matcher(text).find() && PRIVACY_WORDS.matcher(text).find()) {
            out.add(amb(out.size() + 1, "CONFLICT", "tracking vs privacy",
                    "the request asks to identify individuals while also requiring privacy",
                    "Which wins: per-individual tracking or privacy? (they conflict)",
                    "Privacy wins: aggregate counters only, no per-individual tracking", "", ""));
        }
        return out;
    }

    private static Map<String, Object> amb(int n, String kind, String term, String why, String question,
                                           String assumption, String parameter, String defaultValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "A" + n);
        m.put("kind", kind);
        m.put("term", term);
        m.put("why", why);
        m.put("question", question);
        m.put("assumption", assumption);
        m.put("parameter", parameter);
        m.put("default", defaultValue);
        return m;
    }

    private static List<Map<String, Object>> risks(Set<String> c) {
        List<Map<String, Object>> r = new ArrayList<>();
        if (c.contains("proxy") || c.contains("client-ip")) {
            r.add(risk("SPOOFING", "HIGH", "client-controlled headers (X-Forwarded-For) can be forged to evade rate limits"));
        }
        if (c.contains("export")) {
            r.add(risk("CSV_INJECTION", "MEDIUM", "exported cells starting with = + - @ can execute as spreadsheet formulas"));
        }
        if (c.contains("security") || c.contains("create")) {
            r.add(risk("SSRF_OPEN_REDIRECT", "HIGH", "user-supplied URLs can target internal hosts or dangerous schemes"));
        }
        if (c.contains("rate-limit")) {
            r.add(risk("ABUSE_DOS", "MEDIUM", "unthrottled creation or redirects enable resource exhaustion"));
        }
        if (c.contains("privacy") || c.contains("analytics")) {
            r.add(risk("PRIVACY_RETENTION", "MEDIUM", "analytics may capture or retain personal data longer than necessary"));
        }
        return r;
    }

    private static Map<String, Object> risk(String id, String severity, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("severity", severity);
        m.put("text", text);
        return m;
    }
}
