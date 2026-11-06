package com.nullandkale.nullspace.computer;

import static com.nullandkale.nullspace.computer.ScriptLanguage.*;

import java.util.*;
import java.util.regex.*;

/** Immutable, bounded AST. Parsing belongs to a program, never to an execution step. */
public final class CompiledExpression {
  private interface Node {
    Object eval(Map<String, Object> vars, Host host);
  }

  private final Node root;

  private CompiledExpression(Node root) {
    this.root = root;
  }

  public static CompiledExpression compile(String source) {
    var parser = new Parser(source);
    var root = parser.expr(0);
    if (!parser.peek().equals("<end>"))
      throw new IllegalArgumentException("Unexpected token " + parser.peek());
    return new CompiledExpression(root);
  }

  public Object evaluate(Map<String, Object> variables, Host host) {
    return root.eval(variables, host);
  }

  private static final class Parser {
    private static final Pattern TOKEN =
        Pattern.compile(
            "\\s*+(?:([A-Za-z_][A-Za-z0-9_.]*)|(\\d+(?:\\.\\d+)?)|('(?:\\\\.|[^'\\\\])*+'|\"(?:\\\\.|[^\"\\\\])*+\")|(==|!=|<=|>=|//|[+*/%<>()!,\\[\\]-]))");
    final List<String> tokens = new ArrayList<>();
    int at, depth, nodes;

    Parser(String source) {
      var m = TOKEN.matcher(source);
      int end = 0;
      while (end < source.length()) {
        m.region(end, source.length());
        if (!m.lookingAt()) {
          if (source.substring(end).isBlank()) break;
          throw new IllegalArgumentException("Invalid expression");
        }
        tokens.add(
            m.group(1) != null
                ? m.group(1)
                : m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4));
        end = m.end();
        if (tokens.size() > 1024) throw new IllegalArgumentException("Expression is too large");
      }
      tokens.add("<end>");
    }

    String peek() {
      return tokens.get(Math.min(at, tokens.size() - 1));
    }

    String next() {
      if (at >= tokens.size()) throw new IllegalArgumentException("Expected a value");
      return tokens.get(at++);
    }

    void want(String s) {
      if (!next().equals(s)) throw new IllegalArgumentException("Expected " + s);
    }

    Node node(Node n) {
      if (++nodes > 1024) throw new IllegalArgumentException("Expression is too large");
      return n;
    }

    Node expr(int min) {
      if (++depth > 48) throw new IllegalArgumentException("Expression nesting limit reached");
      Node left = atom();
      while (true) {
        String op = peek();
        int precedence =
            switch (op) {
              case "or" -> 1;
              case "and" -> 2;
              case "==", "!=", "<", ">", "<=", ">=" -> 3;
              case "+", "-" -> 4;
              case "*", "/", "//", "%" -> 5;
              default -> -1;
            };
        if (precedence < min) break;
        next();
        Node a = left, b = expr(precedence + 1);
        left =
            node(
                (v, h) -> {
                  Object x = a.eval(v, h);
                  if (op.equals("and") && !truth(x)) return false;
                  if (op.equals("or") && truth(x)) return true;
                  return binary(op, x, b.eval(v, h));
                });
      }
      depth--;
      return left;
    }

    Node atom() {
      String s = next();
      if (s.equals("-")) {
        Node child = expr(6);
        return node((v, h) -> finite(-number(child.eval(v, h))));
      }
      if (s.equals("not") || s.equals("!")) {
        Node child = expr(s.equals("not") ? 3 : 6);
        return node((v, h) -> !truth(child.eval(v, h)));
      }
      if (s.equals("(")) {
        Node child = expr(0);
        want(")");
        return child;
      }
      if (s.startsWith("\"") || s.startsWith("'")) {
        String value = words(s).getFirst();
        return node((v, h) -> value);
      }
      if (s.matches("\\d.*")) {
        double value = finite(Double.parseDouble(s));
        return node((v, h) -> value);
      }
      if (s.equals("True") || s.equals("true")) return node((v, h) -> true);
      if (s.equals("False") || s.equals("false")) return node((v, h) -> false);
      if (s.equals("None") || s.equals("null")) return node((v, h) -> null);
      if (s.equals("[")) {
        List<Node> children = arguments("]");
        return node((v, h) -> values(children, v, h));
      }
      if (!s.matches("[A-Za-z_][A-Za-z0-9_.]*"))
        throw new IllegalArgumentException("Expected a value");
      if (peek().equals("(")) {
        next();
        List<Node> args = arguments(")");
        return node((v, h) -> h.call(s, values(args, v, h)));
      }
      return node(
          (v, h) -> {
            if (!v.containsKey(s)) throw new IllegalArgumentException("Unknown variable: " + s);
            return v.get(s);
          });
    }

    List<Node> arguments(String end) {
      var args = new ArrayList<Node>();
      if (!peek().equals(end)) {
        do {
          args.add(expr(0));
          if (!peek().equals(",")) break;
          next();
        } while (true);
      }
      want(end);
      return List.copyOf(args);
    }
  }

  private static List<Object> values(List<Node> nodes, Map<String, Object> vars, Host host) {
    var result = new ArrayList<Object>(nodes.size());
    for (var node : nodes) result.add(node.eval(vars, host));
    return Collections.unmodifiableList(result);
  }

  static Object binary(String op, Object a, Object b) {
    return switch (op) {
      case "+" -> {
        if (a instanceof String || b instanceof String) {
          checkValue(a, 8192);
          checkValue(b, 8192);
          String left = text(a), right = text(b);
          if (left.length() + right.length() > 8192)
            throw new IllegalArgumentException("String exceeds memory limit");
          yield left + right;
        }
        yield finite(number(a) + number(b));
      }
      case "-" -> finite(number(a) - number(b));
      case "*" -> finite(number(a) * number(b));
      case "/" -> {
        if (number(b) == 0) throw new IllegalArgumentException("Division by zero");
        yield finite(number(a) / number(b));
      }
      case "//" -> {
        if (number(b) == 0) throw new IllegalArgumentException("Division by zero");
        yield Math.floor(number(a) / number(b));
      }
      case "%" -> {
        if (number(b) == 0) throw new IllegalArgumentException("Division by zero");
        yield number(a) % number(b);
      }
      case "==" ->
          Objects.equals(a, b)
              || a instanceof Number && b instanceof Number && number(a) == number(b);
      case "!=" ->
          !(Objects.equals(a, b)
              || a instanceof Number && b instanceof Number && number(a) == number(b));
      case "<" -> number(a) < number(b);
      case ">" -> number(a) > number(b);
      case "<=" -> number(a) <= number(b);
      case ">=" -> number(a) >= number(b);
      case "and" -> truth(a) && truth(b);
      case "or" -> truth(a) || truth(b);
      default -> throw new IllegalArgumentException("Unknown operator");
    };
  }

  static double finite(double n) {
    if (!Double.isFinite(n)) throw new IllegalArgumentException("Numeric overflow");
    return n;
  }
}
