package com.acme.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal dependency-free JSON reader/writer with bounded nesting depth. */
public final class Json {
    private static final int MAX_DEPTH = 32;

    private Json() {
    }

    public static Object parse(String text) {
        P p = new P(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) {
            throw p.err("trailing characters");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("JSON object expected");
        }
        return (Map<String, Object>) v;
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(s, sb);
        } else if (v instanceof Double d) {
            sb.append(d.isNaN() || d.isInfinite() ? "null" : d.toString());
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else {
            quote(v.toString(), sb);
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        sb.append('"');
    }

    private static final class P {
        final String s;
        int i;

        P(String s) {
            this.s = s;
        }

        IllegalArgumentException err(String m) {
            return new IllegalArgumentException(m + " at position " + i);
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        char peek() {
            if (i >= s.length()) {
                throw err("unexpected end of input");
            }
            return s.charAt(i);
        }

        char next() {
            char c = peek();
            i++;
            return c;
        }

        void expect(char c) {
            if (next() != c) {
                i--;
                throw err("expected '" + c + "'");
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw err("nesting too deep");
            }
            char c = peek();
            if (c == '{') {
                return object(depth);
            } else if (c == '[') {
                return array(depth);
            } else if (c == '"') {
                return string();
            } else if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            } else if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            } else if (s.startsWith("null", i)) {
                i += 4;
                return null;
            } else if (c == '-' || Character.isDigit(c)) {
                return number();
            }
            throw err("unexpected character '" + c + "'");
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                if (peek() != '"') {
                    throw err("object key must be a string");
                }
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value(depth + 1));
                ws();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    i--;
                    throw err("expected ',' or '}'");
                }
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                char c = next();
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    i--;
                    throw err("expected ',' or ']'");
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw err("bad unicode escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw err("bad unicode escape");
                            }
                            i += 4;
                        }
                        default -> throw err("bad escape");
                    }
                } else if (c < 0x20) {
                    throw err("control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = i;
            boolean dbl = false;
            i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) {
                dbl |= !Character.isDigit(s.charAt(i));
                i++;
            }
            try {
                String n = s.substring(start, i);
                return dbl ? (Object) Double.valueOf(n) : (Object) Long.valueOf(n);
            } catch (NumberFormatException e) {
                i = start;
                throw err("invalid number");
            }
        }
    }
}
