package dev.pointledger.util;

import java.lang.reflect.RecordComponent;
import java.util.*;

/** Small, strict JSON reader/writer so the service has zero third-party dependencies. */
public final class Json {
    private Json() {}

    // ---------------------------------------------------------------- writing

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(o, sb);
        return sb.toString();
    }

    private static void write(Object o, StringBuilder sb) {
        switch (o) {
            case null -> sb.append("null");
            case String s -> str(s, sb);
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            case Enum<?> e -> str(e.name(), sb);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    str(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            }
            case Collection<?> c -> {
                sb.append('[');
                boolean first = true;
                for (Object x : c) {
                    if (!first) sb.append(',');
                    first = false;
                    write(x, sb);
                }
                sb.append(']');
            }
            case Record r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (RecordComponent rc : r.getClass().getRecordComponents()) {
                    try { m.put(rc.getName(), rc.getAccessor().invoke(r)); }
                    catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
                }
                write(m, sb);
            }
            default -> str(o.toString(), sb);
        }
    }

    private static void str(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- reading

    /** Parses to Map / List / String / Long / Double / Boolean / null. */
    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) throw p.err("trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) o;
    }

    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof String s)) throw new IllegalArgumentException("field '" + k + "' must be a string");
        return s;
    }

    public static String strOr(Map<String, Object> m, String k, String dflt) {
        Object v = m.get(k);
        return v == null ? dflt : str(m, k);
    }

    public static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Long l)) throw new IllegalArgumentException("field '" + k + "' must be an integer");
        return l;
    }

    public static long numOr(Map<String, Object> m, String k, long dflt) {
        return m.get(k) == null ? dflt : num(m, k);
    }

    private static final class Parser {
        final String s;
        int i;
        int depth;

        Parser(String s) { this.s = s; }

        IllegalArgumentException err(String m) { return new IllegalArgumentException("bad JSON at " + i + ": " + m); }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> lit("true", Boolean.TRUE);
                case 'f' -> lit("false", Boolean.FALSE);
                case 'n' -> lit("null", null);
                default -> number();
            };
        }

        Object lit(String w, Object v) {
            if (!s.startsWith(w, i)) throw err("expected " + w);
            i += w.length();
            return v;
        }

        Map<String, Object> object() {
            if (++depth > 64) throw err("nesting too deep");
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek('}')) { i++; depth--; return m; }
            while (true) {
                ws();
                if (!peek('"')) throw err("expected key");
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                if (peek(',')) { i++; continue; }
                expect('}');
                depth--;
                return m;
            }
        }

        List<Object> array() {
            if (++depth > 64) throw err("nesting too deep");
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (peek(']')) { i++; depth--; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (peek(',')) { i++; continue; }
                expect(']');
                depth--;
                return l;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw err("bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw err("bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("bad escape");
                }
            }
        }

        Object number() {
            int start = i;
            if (peek('-')) i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(start, i);
            if (t.isEmpty() || t.equals("-")) throw err("unexpected character");
            try {
                if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
                return Long.parseLong(t);
            } catch (NumberFormatException ex) {
                throw err("bad number " + t);
            }
        }

        boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

        void expect(char c) {
            if (!peek(c)) throw err("expected '" + c + "'");
            i++;
        }
    }
}
