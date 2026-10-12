package com.acme.sdlc;

import java.util.ArrayList;
import java.util.List;

/** Small LCS-based line diff producing a readable unified-style listing for review artifacts. */
public final class SimpleDiff {
    private SimpleDiff() {
    }

    public record Stats(int added, int removed) {
    }

    public static Stats stats(String before, String after) {
        int a = 0;
        int r = 0;
        for (String o : ops(lines(before), lines(after))) {
            a += o.startsWith("+") ? 1 : 0;
            r += o.startsWith("-") ? 1 : 0;
        }
        return new Stats(a, r);
    }

    public static String unified(String path, String before, String after) {
        List<String> ops = ops(lines(before), lines(after));
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(before.isEmpty() ? "/dev/null" : "a/" + path).append('\n');
        sb.append("+++ b/").append(path).append('\n');
        boolean[] show = new boolean[ops.size()];
        for (int i = 0; i < ops.size(); i++) {
            if (!ops.get(i).startsWith(" ")) {
                for (int j = Math.max(0, i - 3); j <= Math.min(ops.size() - 1, i + 3); j++) {
                    show[j] = true;
                }
            }
        }
        boolean gap = false;
        for (int i = 0; i < ops.size(); i++) {
            if (show[i]) {
                if (gap) {
                    sb.append("@@\n");
                    gap = false;
                }
                sb.append(ops.get(i)).append('\n');
            } else {
                gap = true;
            }
        }
        return sb.toString();
    }

    private static List<String> lines(String s) {
        return s == null || s.isEmpty() ? List.of() : List.of(s.split("\n", -1));
    }

    private static List<String> ops(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        List<String> out = new ArrayList<>();
        if ((long) n * m > 4_000_000L) { // too large for the DP table: degrade to whole-file replacement
            a.forEach(l -> out.add("-" + l));
            b.forEach(l -> out.add("+" + l));
            return out;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                out.add(" " + a.get(i++));
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add("-" + a.get(i++));
            } else {
                out.add("+" + b.get(j++));
            }
        }
        while (i < n) {
            out.add("-" + a.get(i++));
        }
        while (j < m) {
            out.add("+" + b.get(j++));
        }
        return out;
    }
}
