package p2p;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JsonUtil {
    private JsonUtil() {}

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < ' ') {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }

    public static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    public static Map<String, String> parseSimpleJson(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.isBlank()) {
            return map;
        }
        // Match "key"\s*:\s*("value"|number|true|false|null)
        Pattern pattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"((?:\\\\\"|[^\"])*)\"|([0-9a-zA-Z._-]+))");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            String key = matcher.group(1);
            String stringVal = matcher.group(2);
            String literalVal = matcher.group(3);
            if (stringVal != null) {
                map.put(key, unescape(stringVal));
            } else if (literalVal != null) {
                map.put(key, literalVal);
            }
        }
        return map;
    }

    public static String unescape(String input) {
        if (input == null || !input.contains("\\")) {
            return input;
        }
        StringBuilder sb = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaping) {
                switch (c) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 < input.length()) {
                            String hex = input.substring(i + 1, i + 5);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append("\\u");
                            }
                        } else {
                            sb.append("\\u");
                        }
                    }
                    default -> sb.append(c);
                }
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
