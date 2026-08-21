package net.fliver.livechat.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal hand-rolled JSON reader/writer - no Gson/Jackson dependency, same
 * reasoning as the sibling proprietary plugin's net.fliver.plugin.util.Json:
 * avoids classpath collisions with whatever JSON library other plugins on
 * the same server might already shade at a different version. Only handles
 * what PROTOCOL.md's payloads need: objects, arrays, strings, numbers,
 * booleans, null - no comments, no trailing commas, no streaming.
 */
public final class Json {
  private Json() {}

  public static Object parse(String text) {
    Parser parser = new Parser(text);
    Object value = parser.parseValue();
    parser.skipWhitespace();
    if (!parser.atEnd()) {
      throw new IllegalArgumentException("Trailing content after JSON value at " + parser.pos);
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> asObject(Object value) {
    if (value instanceof Map) return (Map<String, Object>) value;
    throw new IllegalArgumentException("Expected a JSON object.");
  }

  @SuppressWarnings("unchecked")
  public static List<Object> asArray(Object value) {
    if (value instanceof List) return (List<Object>) value;
    throw new IllegalArgumentException("Expected a JSON array.");
  }

  public static String asString(Object value, String fallback) {
    return value instanceof String ? (String) value : fallback;
  }

  public static boolean asBoolean(Object value, boolean fallback) {
    return value instanceof Boolean ? (Boolean) value : fallback;
  }

  public static Map<String, Object> newObject() {
    return new LinkedHashMap<>();
  }

  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    writeValue(value, out);
    return out.toString();
  }

  @SuppressWarnings("unchecked")
  private static void writeValue(Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String) {
      writeString((String) value, out);
    } else if (value instanceof Boolean || value instanceof Number) {
      out.append(value);
    } else if (value instanceof Map) {
      writeObject((Map<String, Object>) value, out);
    } else if (value instanceof List) {
      writeArray((List<Object>) value, out);
    } else {
      writeString(String.valueOf(value), out);
    }
  }

  private static void writeObject(Map<String, Object> map, StringBuilder out) {
    out.append('{');
    boolean first = true;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (!first) out.append(',');
      first = false;
      writeString(entry.getKey(), out);
      out.append(':');
      writeValue(entry.getValue(), out);
    }
    out.append('}');
  }

  private static void writeArray(List<Object> list, StringBuilder out) {
    out.append('[');
    boolean first = true;
    for (Object item : list) {
      if (!first) out.append(',');
      first = false;
      writeValue(item, out);
    }
    out.append(']');
  }

  private static void writeString(String s, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  private static final class Parser {
    private final String text;
    private int pos;

    Parser(String text) {
      this.text = text;
      this.pos = 0;
    }

    boolean atEnd() {
      return pos >= text.length();
    }

    void skipWhitespace() {
      while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    char peek() {
      if (pos >= text.length()) {
        throw new IllegalArgumentException("Unexpected end of JSON input.");
      }
      return text.charAt(pos);
    }

    void expect(char c) {
      if (peek() != c) {
        throw new IllegalArgumentException("Expected '" + c + "' at " + pos);
      }
      pos++;
    }

    Object parseValue() {
      skipWhitespace();
      char c = peek();
      return switch (c) {
        case '{' -> parseObject();
        case '[' -> parseArray();
        case '"' -> parseString();
        case 't', 'f' -> parseBoolean();
        case 'n' -> parseNull();
        default -> parseNumber();
      };
    }

    Map<String, Object> parseObject() {
      Map<String, Object> map = new LinkedHashMap<>();
      expect('{');
      skipWhitespace();
      if (peek() == '}') {
        pos++;
        return map;
      }
      while (true) {
        skipWhitespace();
        String key = parseString();
        skipWhitespace();
        expect(':');
        Object value = parseValue();
        map.put(key, value);
        skipWhitespace();
        char next = peek();
        pos++;
        if (next == '}') break;
        if (next != ',') throw new IllegalArgumentException("Expected ',' or '}' at " + pos);
      }
      return map;
    }

    List<Object> parseArray() {
      List<Object> list = new ArrayList<>();
      expect('[');
      skipWhitespace();
      if (peek() == ']') {
        pos++;
        return list;
      }
      while (true) {
        list.add(parseValue());
        skipWhitespace();
        char next = peek();
        pos++;
        if (next == ']') break;
        if (next != ',') throw new IllegalArgumentException("Expected ',' or ']' at " + pos);
      }
      return list;
    }

    String parseString() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        char c = peek();
        pos++;
        if (c == '"') break;
        if (c == '\\') {
          char escape = peek();
          pos++;
          switch (escape) {
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case '/' -> sb.append('/');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'u' -> {
              String hex = text.substring(pos, pos + 4);
              pos += 4;
              sb.append((char) Integer.parseInt(hex, 16));
            }
            default -> throw new IllegalArgumentException("Invalid escape at " + pos);
          }
        } else {
          sb.append(c);
        }
      }
      return sb.toString();
    }

    Boolean parseBoolean() {
      if (text.startsWith("true", pos)) {
        pos += 4;
        return Boolean.TRUE;
      }
      if (text.startsWith("false", pos)) {
        pos += 5;
        return Boolean.FALSE;
      }
      throw new IllegalArgumentException("Invalid literal at " + pos);
    }

    Object parseNull() {
      if (text.startsWith("null", pos)) {
        pos += 4;
        return null;
      }
      throw new IllegalArgumentException("Invalid literal at " + pos);
    }

    Double parseNumber() {
      int start = pos;
      if (peek() == '-') pos++;
      while (pos < text.length() && "0123456789.eE+-".indexOf(text.charAt(pos)) >= 0) pos++;
      String number = text.substring(start, pos);
      if (number.isEmpty()) throw new IllegalArgumentException("Invalid number at " + pos);
      return Double.parseDouble(number);
    }
  }
}
