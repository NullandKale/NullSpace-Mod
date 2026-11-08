package com.nullandkale.nullspace.model.computer;

import java.util.*;
import java.util.regex.*;

/** Two bounded syntaxes compiled to the same resumable statement program. No host evaluator. */
public final class ScriptLanguage
{
    public enum Syntax
    {
        BASH, PYTHON
    }

    public record Instruction(int line, String operation, String name, String expression, int jump)
    {}

    public static final int COMPILER_ABI = 1;

    public record Program(List<Instruction> code, Map<Integer, CompiledExpression> expressions)
    {
        public Program(List<Instruction> code)
        {
            this(List.copyOf(code), compileExpressions(code));
        }

        public Program
        {
            code = List.copyOf(code);
            expressions = Map.copyOf(expressions);
        }

        private static Map<Integer, CompiledExpression> compileExpressions(List<Instruction> code)
        {
            var result = new HashMap<Integer, CompiledExpression>();
            for (int i = 0; i < code.size(); i++)
            {
                var op = code.get(i);
                if (Set.of("set", "test", "eval").contains(op.operation))
                    try
                    {
                        result.put(i, CompiledExpression.compile(op.expression));
                    }
                    catch (IllegalArgumentException e)
                    {
                        throw error(op.line, e.getMessage());
                    }
            }
            return result;
        }

        Object evaluate(int pc, Map<String, Object> variables, Host host)
        {
            return expressions.get(pc).evaluate(variables, host);
        }
    }

    public interface Host
    {
        Object call(String name, List<Object> arguments);

        String shell(String command, Map<String, Object> variables);
    }

    public static final class Unavailable extends RuntimeException
    {
        public final int retryTicks;

        public Unavailable(String message)
        {
            this(message, 20);
        }

        public Unavailable(String message, int retryTicks)
        {
            super(message);
            if (retryTicks < 1 || retryTicks > 72000)
                throw new IllegalArgumentException("Invalid retry delay");
            this.retryTicks = retryTicks;
        }
    }

    private record Block(String kind, int indent, int start, int branch, String increment)
    {}

    public static Program compile(String source, Syntax syntax)
    {
        if (source.length() > VirtualFiles.MAX_FILE)
            throw new IllegalArgumentException("Script exceeds 16 KiB");
        var code = new ArrayList<Instruction>();
        var blocks = new ArrayDeque<Block>();
        int number = 0;
        boolean needsBody = false;
        for (String raw : source.replace("\r", "").split("\n", -1))
        {
            number++;
            if (raw.indexOf('\t') >= 0 && syntax == Syntax.PYTHON)
                throw error(number, "Use spaces for indentation");
            int indent = raw.length() - raw.stripLeading().length();
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            if (syntax == Syntax.PYTHON)
            {
                if (needsBody && (!blocks.isEmpty() && indent <= blocks.peek().indent))
                    throw error(number, "Expected an indented block");
                needsBody = false;
                while (!blocks.isEmpty() && indent <= blocks.peek().indent && !(line.equals("else:") && indent == blocks.peek().indent && blocks.peek().kind.equals("if") && blocks.peek().branch < 0))
                    close(code, blocks.pop());
                if (blocks.isEmpty() && indent > 0)
                    throw error(number, "Unexpected indentation");
            }
            if (blocks.size() > 32 || code.size() > 2048)
                throw error(number, "Script is too complex");
            if (line.equals("else:") || line.equals("else"))
            {
                if (blocks.isEmpty() || !blocks.peek().kind.equals("if") || blocks.peek().branch >= 0 || syntax == Syntax.PYTHON && indent != blocks.peek().indent)
                    throw error(number, "Unexpected else");
                var b = blocks.pop();
                int jump = code.size();
                code.add(new Instruction(number, "jump", "", "", -1));
                patch(code, b.start, jump + 1);
                blocks.push(new Block("if", b.indent, b.start, jump, ""));
                needsBody = syntax == Syntax.PYTHON;
                continue;
            }
            if (syntax == Syntax.BASH && (line.equals("fi") || line.equals("done")))
            {
                if (blocks.isEmpty() || line.equals("fi") != blocks.peek().kind.equals("if"))
                    throw error(number, "Mismatched block ending");
                close(code, blocks.pop());
                continue;
            }
            if (syntax == Syntax.BASH && (line.equals("then") || line.equals("do")))
                continue;
            if (line.startsWith("if ") || line.startsWith("while "))
            {
                boolean loop = line.startsWith("while ");
                String condition = line.substring(loop ? 6 : 3).strip();
                if (syntax == Syntax.PYTHON)
                {
                    if (!condition.endsWith(":"))
                        throw error(number, "Expected ':'");
                    condition = condition.substring(0, condition.length() - 1);
                }
                else
                    condition = condition.replaceFirst(";?\\s*(then|do)$", "").strip();
                int at = code.size();
                code.add(new Instruction(number, "test", "", syntax == Syntax.BASH ? bashCondition(condition) : condition, -1));
                blocks.push(new Block(loop ? "while" : "if", indent, at, -1, ""));
                needsBody = syntax == Syntax.PYTHON;
                continue;
            }
            var range = Pattern.compile("for\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s+range\\((.*)\\):").matcher(line);
            if (syntax == Syntax.PYTHON && range.matches())
            {
                String variable = range.group(1), bound = "_bound" + number;
                code.add(new Instruction(number, "set", variable, "0", 0));
                code.add(new Instruction(number, "set", bound, range.group(2), 0));
                int at = code.size();
                code.add(new Instruction(number, "test", "", variable + " < " + bound, -1));
                blocks.push(new Block("while", indent, at, -1, variable));
                needsBody = true;
                continue;
            }
            if (line.equals("break") || line.equals("continue"))
                throw error(number, "Use a condition to end loops in this language version");
            var assignment = Pattern.compile("(?:let\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=(?!=)\\s*(.*)").matcher(line);
            if (assignment.matches())
            {
                String expr = assignment.group(2);
                if (syntax == Syntax.BASH)
                {
                    if (expr.startsWith("$((") && expr.endsWith("))"))
                        expr = expr.substring(3, expr.length() - 2);
                    else
                        expr = bashValue(expr);
                }
                code.add(new Instruction(number, "set", assignment.group(1), expr, 0));
            }
            else if (syntax == Syntax.PYTHON)
                code.add(new Instruction(number, "eval", "", line, 0));
            else
                code.add(new Instruction(number, "shell", "", line, 0));
        }
        if (needsBody)
            throw error(number, "Expected an indented block");
        if (syntax == Syntax.BASH && !blocks.isEmpty())
            throw error(number, "Unclosed " + blocks.peek().kind + " block");
        while (!blocks.isEmpty())
            close(code, blocks.pop());
        return new Program(code);
    }

    private static void close(List<Instruction> code, Block b)
    {
        if (b.kind.equals("while"))
        {
            if (!b.increment.isEmpty())
                code.add(new Instruction(code.isEmpty() ? 1 : code.getLast().line, "set", b.increment, b.increment + " + 1", 0));
            code.add(new Instruction(code.isEmpty() ? 1 : code.getLast().line, "jump", "", "", b.start));
            patch(code, b.start, code.size());
        }
        else
            patch(code, b.branch < 0 ? b.start : b.branch, code.size());
    }

    private static void patch(List<Instruction> code, int i, int target)
    {
        var old = code.get(i);
        code.set(i, new Instruction(old.line, old.operation, old.name, old.expression, target));
    }

    private static IllegalArgumentException error(int line, String text)
    {
        return new IllegalArgumentException("Line " + line + ": " + text);
    }

    private static String bashValue(String value)
    {
        if (value.matches("-?\\d+(?:\\.\\d+)?") || value.equals("true") || value.equals("false"))
            return value;
        if (value.startsWith("$") && !value.startsWith("${"))
            return value.substring(1);
        if (value.startsWith("\"") || value.startsWith("'"))
            return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String bashCondition(String s)
    {
        s = s.strip();
        if (s.startsWith("[") && s.endsWith("]"))
            s = s.substring(1, s.length() - 1).strip();
        s = s.replace("\"$", "$").replaceAll("(\\$[A-Za-z_][A-Za-z0-9_]*)\"", "$1");
        for (var p : Map.of("-lt", "<", "-le", "<=", "-gt", ">", "-ge", ">=", "-eq", "==", "-ne", "!=").entrySet())
            s = s.replace(" " + p.getKey() + " ", " " + p.getValue() + " ");
        return s.replaceAll("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}", "$1").replaceAll("\\$([A-Za-z_][A-Za-z0-9_]*)", "$1");
    }

    /** Validate guest graphs iteratively before any recursive Java formatting can run. */
    public static void checkValue(Object value, int limit)
    {
        record Item(Object value, int depth)
        {}
        var queue = new ArrayDeque<Item>();
        queue.push(new Item(value, 0));
        int nodes = 0, bytes = 0;
        while (!queue.isEmpty())
        {
            var item = queue.pop();
            if (++nodes > 4096 || item.depth > 32)
                throw new IllegalArgumentException("Value nesting or item limit reached");
            Object v = item.value;
            if (v == null)
                continue;
            if (v instanceof String str)
                bytes += str.length();
            else if (v instanceof Number n)
            {
                if (!Double.isFinite(n.doubleValue()))
                    throw new IllegalArgumentException("Numeric overflow");
                bytes += 24;
            }
            else if (v instanceof Boolean)
                bytes += 5;
            else if (v instanceof List<?> list)
            {
                if (list.size() > 1024)
                    throw new IllegalArgumentException("List limit reached");
                bytes += list.size() * 2;
                for (var child : list)
                    queue.push(new Item(child, item.depth + 1));
            }
            else if (v instanceof Map<?, ?> map)
            {
                if (map.size() > 128)
                    throw new IllegalArgumentException("Map limit reached");
                for (var e : map.entrySet())
                {
                    if (!(e.getKey() instanceof String key))
                        throw new IllegalArgumentException("Invalid map key");
                    bytes += key.length() + 4;
                    queue.push(new Item(e.getValue(), item.depth + 1));
                }
            }
            else
                throw new IllegalArgumentException("Unsupported guest value");
            if (bytes > limit)
                throw new IllegalArgumentException("Value exceeds memory limit");
        }
    }

    public static boolean truth(Object o)
    {
        return o != null && !Boolean.FALSE.equals(o) && (!(o instanceof Number n) || n.doubleValue() != 0) && (!(o instanceof String s) || !s.isEmpty());
    }

    public static String text(Object value)
    {
        if (value == null)
            return "";
        if (value instanceof Double n && n == Math.rint(n))
            return Long.toString(n.longValue());
        return value.toString();
    }

    public static double number(Object value)
    {
        if (value instanceof Number n)
            return n.doubleValue();
        try
        {
            return Double.parseDouble(text(value));
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Expected a number, got " + text(value));
        }
    }

    public static final class Job
    {
        public final Program program;
        public final LinkedHashMap<String, Object> variables = new LinkedHashMap<>();
        public int pc;
        public long wake;
        public boolean done, detached;
        public String failure = "", waiting = "";

        public Job(Program program)
        {
            this.program = program;
        }

        public int step(Host host, long tick, int budget)
        {
            int used = 0;
            while (!done && tick >= wake && used < budget)
            {
                if (pc >= program.code.size())
                {
                    done = true;
                    break;
                }
                var op = program.code.get(pc);
                used++;
                waiting = "";
                try
                {
                    switch (op.operation)
                    {
                        case "set" ->
                        {
                            if (variables.size() >= 128 && !variables.containsKey(op.name))
                                throw new IllegalArgumentException("Variable limit reached");
                            var value = program.evaluate(pc, variables, host);
                            checkValue(value, 8192);
                            var candidate = new LinkedHashMap<String, Object>(variables);
                            candidate.put(op.name, value);
                            checkValue(candidate, 65536);
                            variables.put(op.name, value);
                            pc++;
                        }
                        case "test" -> pc = truth(program.evaluate(pc, variables, host)) ? pc + 1 : op.jump;
                        case "jump" -> pc = op.jump;
                        case "eval" ->
                        {
                            program.evaluate(pc, variables, wrapSleep(host, tick));
                            pc++;
                        }
                        case "shell" ->
                        {
                            var words = words(op.expression);
                            if (!words.isEmpty() && words.getFirst().equals("sleep"))
                            {
                                if (words.size() != 2)
                                    throw new IllegalArgumentException("sleep <seconds>");
                                wake = tick + (long) Math.clamp(number(expand(words.get(1), variables)) * 20, 1, 72000);
                                pc++;
                            }
                            else
                            {
                                String output = host.shell(op.expression, variables);
                                if (!output.isEmpty())
                                    host.call("print", List.of(output));
                                pc++;
                            }
                        }
                        default -> throw new IllegalArgumentException("Unknown instruction");
                    }
                }
                catch (Unavailable unavailable)
                {
                    waiting = unavailable.getMessage();
                    wake = tick + unavailable.retryTicks;
                    break;
                }
                catch (RuntimeException e)
                {
                    failure = "Line " + op.line + ": " + e.getMessage();
                    done = true;
                }
            }
            return used;
        }

        private Host wrapSleep(Host host, long tick)
        {
            return new Host()
            {
                public Object call(String name, List<Object> args)
                {
                    if (name.equals("sleep"))
                    {
                        if (args.size() != 1)
                            throw new IllegalArgumentException("sleep(seconds)");
                        wake = tick + (long) Math.clamp(number(args.getFirst()) * 20, 1, 72000);
                        return null;
                    }
                    return host.call(name, args);
                }

                public String shell(String command, Map<String, Object> vars)
                {
                    return host.shell(command, vars);
                }
            };
        }
    }

    public static String expand(String line, Map<String, Object> variables)
    {
        var m = Pattern.compile("(?<!\u0002)\\$\\{([A-Za-z_][A-Za-z0-9_]*)}|(?<!\u0002)\\$([A-Za-z_][A-Za-z0-9_]*)").matcher(line);
        var b = new StringBuilder();
        int previous = 0;
        while (m.find())
        {
            String key = m.group(1) == null ? m.group(2) : m.group(1);
            String replacement = text(variables.getOrDefault(key, ""));
            if (b.length() + m.start() - previous + replacement.length() > 32768)
                throw new IllegalArgumentException("Expansion exceeds memory limit");
            m.appendReplacement(b, Matcher.quoteReplacement(replacement));
            previous = m.end();
        }
        if (b.length() + line.length() - previous > 32768)
            throw new IllegalArgumentException("Expansion exceeds memory limit");
        m.appendTail(b);
        return b.toString().replace("\u0002", "");
    }

    public static List<String> words(String command)
    {
        return words(command, false);
    }

    public static List<String> words(String command, boolean shell)
    {
        var result = new ArrayList<String>();
        var b = new StringBuilder();
        char quote = 0;
        boolean escape = false, started = false;
        for (int i = 0; i < command.length(); i++)
        {
            char c = command.charAt(i);
            if (escape)
            {
                b.append(switch (c)
                {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> c;
                });
                escape = false;
                started = true;
            }
            else if (c == '\\' && quote != '\'')
            {
                escape = true;
                started = true;
            }
            else if (quote != 0)
            {
                if (c == quote)
                    quote = 0;
                else
                {
                    if (shell && quote == '\'' && c == '$')
                        b.append('\u0002');
                    b.append(c);
                }
                started = true;
            }
            else if (c == '\'' || c == '\"')
            {
                quote = c;
                started = true;
            }
            else if (Character.isWhitespace(c))
            {
                if (started)
                {
                    result.add(b.toString());
                    b.setLength(0);
                    started = false;
                }
            }
            else if (c == '|' || c == '>')
            {
                if (started)
                {
                    result.add(b.toString());
                    b.setLength(0);
                    started = false;
                }
                if (c == '>' && i + 1 < command.length() && command.charAt(i + 1) == '>')
                {
                    result.add("\u0001>>");
                    i++;
                }
                else
                    result.add("\u0001" + c);
            }
            else
            {
                b.append(c);
                started = true;
            }
        }
        if (quote != 0 || escape)
            throw new IllegalArgumentException("Unclosed quote or escape");
        if (started)
            result.add(b.toString());
        if (result.size() > 256)
            throw new IllegalArgumentException("Too many command arguments");
        return List.copyOf(result);
    }

    public static Object evaluate(String expression, Map<String, Object> variables, Host host)
    {
        return CompiledExpression.compile(expression).evaluate(variables, host);
    }

    private ScriptLanguage()
    {
    }
}
