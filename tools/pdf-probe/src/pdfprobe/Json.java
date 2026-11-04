package pdfprobe;

import java.util.List;
import java.util.Map;

/** Tiny JSON serializer for maps/lists/strings/numbers/booleans. */
public final class Json {
    private Json() {}

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            quote((String) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Number) {
            if (value instanceof Double || value instanceof Float) {
                double d = ((Number) value).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    sb.append("null");
                } else {
                    sb.append(String.format(java.util.Locale.ROOT, "%.2f", d));
                }
            } else {
                sb.append(value);
            }
        } else if (value instanceof Map) {
            writeObject((Map<?, ?>) value, sb, indent);
        } else if (value instanceof List) {
            writeArray((List<?>) value, sb, indent);
        } else {
            quote(String.valueOf(value), sb);
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder sb, int indent) {
        sb.append('{');
        if (!map.isEmpty()) {
            sb.append('\n');
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                indent(sb, indent + 1);
                quote(String.valueOf(e.getKey()), sb);
                sb.append(": ");
                write(e.getValue(), sb, indent + 1);
                if (++i < map.size()) sb.append(',');
                sb.append('\n');
            }
            indent(sb, indent);
        }
        sb.append('}');
    }

    private static void writeArray(List<?> list, StringBuilder sb, int indent) {
        sb.append('[');
        if (!list.isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < list.size(); i++) {
                indent(sb, indent + 1);
                write(list.get(i), sb, indent + 1);
                if (i + 1 < list.size()) sb.append(',');
                sb.append('\n');
            }
            indent(sb, indent);
        }
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append("  ");
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
