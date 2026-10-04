package orbis.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric's dependency version predicates (fabric.mod.json "depends" values): "*", an exact
 * version, comparisons (>=, >, <=, <, =), "~1.2" (same minor), "^1.2" (same major), "1.18.x"
 * wildcards, space-separated conjunctions; a JSON array of them is a disjunction. Versions compare
 * as semantic versions: numeric parts, then a pre-release below its release; build metadata
 * (+...) is ignored. Anything unparseable matches, so an odd predicate never blocks an install.
 */
final class VersionPredicate {
    private VersionPredicate() {}

    /** predicate is the raw JSON value: a string, or an array of strings written as JSON. */
    static boolean matches(String version, String predicate) {
        String p = predicate.trim();
        if (p.startsWith("[")) {
            List<String> alternatives = new ArrayList<>();
            for (String part : p.substring(1, p.length() - 1).split(","))
                alternatives.add(part.trim().replaceAll("^\"|\"$", ""));
            if (alternatives.isEmpty() || (alternatives.size() == 1 && alternatives.get(0).isEmpty())) return true;
            for (String a : alternatives) if (matches(version, a)) return true;
            return false;
        }
        for (String term : p.split("\\s+"))
            if (!term.isEmpty() && !term(version, term)) return false;
        return true;
    }

    private static boolean term(String version, String term) {
        if (term.equals("*")) return true;
        String op = "";
        for (String candidate : new String[]{">=", "<=", ">", "<", "=", "~", "^"})
            if (term.startsWith(candidate)) { op = candidate; break; }
        String target = term.substring(op.length()).trim();
        if (target.contains("x") || target.contains("X") || target.contains("*")) {
            // 1.18.x: every listed number equal, the rest free.
            String[] t = core(target).split("\\.");
            String[] v = core(version).split("\\.");
            for (int i = 0; i < t.length; i++) {
                if (t[i].equalsIgnoreCase("x") || t[i].equals("*")) return true;
                if (i >= v.length || !t[i].equals(v[i])) return false;
            }
            return true;
        }
        Integer c = compare(version, target);
        if (c == null) return true;
        switch (op) {
            case ">=": return c >= 0;
            case "<=": return c <= 0;
            case ">": return c > 0;
            case "<": return c < 0;
            case "~": return c >= 0 && sameParts(version, target, 2);
            case "^": return c >= 0 && sameParts(version, target, 1);
            default: return c == 0;
        }
    }

    private static boolean sameParts(String version, String target, int parts) {
        long[] v = numbers(version), t = numbers(target);
        if (v == null || t == null) return true;
        for (int i = 0; i < parts; i++)
            if ((i < v.length ? v[i] : 0) != (i < t.length ? t[i] : 0)) return false;
        return true;
    }

    /** Semantic comparison, or null when either side has no numeric core. */
    static Integer compare(String a, String b) {
        long[] x = numbers(a), y = numbers(b);
        if (x == null || y == null) return null;
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return Long.compare(p, q);
        }
        String pa = pre(a), pb = pre(b);
        if (pa.isEmpty() || pb.isEmpty()) return pa.isEmpty() == pb.isEmpty() ? 0 : pa.isEmpty() ? 1 : -1;
        return comparePre(pa, pb);
    }

    private static int comparePre(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean nx = x[i].matches("\\d+"), ny = y[i].matches("\\d+");
            int c = nx && ny ? Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]))
                    : nx ? -1 : ny ? 1 : x[i].compareTo(y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    }

    private static String core(String version) {
        String v = version;
        int plus = v.indexOf('+');
        if (plus >= 0) v = v.substring(0, plus);
        int dash = v.indexOf('-');
        return dash >= 0 ? v.substring(0, dash) : v;
    }

    private static String pre(String version) {
        String v = version;
        int plus = v.indexOf('+');
        if (plus >= 0) v = v.substring(0, plus);
        int dash = v.indexOf('-');
        return dash >= 0 ? v.substring(dash + 1) : "";
    }

    private static long[] numbers(String version) {
        String[] parts = core(version).split("\\.");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("\\d{1,18}")) return i == 0 ? null : java.util.Arrays.copyOf(out, i);
            out[i] = Long.parseLong(parts[i]);
        }
        return out;
    }
}
