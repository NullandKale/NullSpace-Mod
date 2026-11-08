package com.nullandkale.nullspace.model.computer;

import java.util.*;

/** An in-memory namespace. Guest paths are never passed to the host filesystem. */
public final class VirtualFiles
{
    public static final int MAX_FILES = 64, MAX_FILE = 16384, MAX_BYTES = 131072;
    private long revision;

    public long revision()
    {
        return revision;
    }

    private final TreeMap<String, String> files = new TreeMap<>();
    private final TreeSet<String> directories = new TreeSet<>(List.of("/", "/home", "/home/crew", "/etc", "/dev", "/dev/ship", "/proc"));

    public static String path(String cwd, String name)
    {
        if (name.length() > 256 || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Invalid virtual path");
        var parts = new ArrayDeque<String>();
        for (String p : (name.startsWith("/") ? name : cwd + "/" + name).split("/"))
        {
            if (p.isEmpty() || p.equals("."))
                continue;
            if (p.equals(".."))
            {
                if (!parts.isEmpty())
                    parts.removeLast();
            }
            else
            {
                if (!p.matches("[A-Za-z0-9_.-]+"))
                    throw new IllegalArgumentException("Use simple virtual file names");
                parts.add(p);
            }
        }
        return "/" + String.join("/", parts);
    }

    private static String parent(String p)
    {
        return p.substring(0, Math.max(1, p.lastIndexOf('/')));
    }

    public void mkdir(String p)
    {
        if (directories.contains(p))
            return;
        if (!p.startsWith("/home/") && !p.startsWith("/etc/"))
            throw new IllegalArgumentException("This virtual path is read-only");
        if (files.containsKey(p))
            throw new IllegalArgumentException("A file already exists there");
        if (!directories.contains(parent(p)))
            throw new IllegalArgumentException("Parent directory does not exist");
        if (directories.size() >= 64)
            throw new IllegalArgumentException("Directory limit reached");
        if (directories.add(p))
            revision++;
    }

    public boolean directory(String p)
    {
        return directories.contains(p);
    }

    public String read(String p)
    {
        var text = files.get(p);
        if (text == null)
            throw new IllegalArgumentException("No virtual file: " + p);
        return text;
    }

    public void write(String p, String text)
    {
        if (!p.startsWith("/home/") && !p.startsWith("/etc/"))
            throw new IllegalArgumentException("This virtual path is read-only");
        if (!directories.contains(parent(p)) || directories.contains(p))
            throw new IllegalArgumentException("Invalid file location");
        if (text.length() > MAX_FILE || files.size() >= MAX_FILES && !files.containsKey(p) || files.values().stream().mapToInt(String::length).sum() - files.getOrDefault(p, "").length() + text.length() > MAX_BYTES)
            throw new IllegalArgumentException("Virtual disk is full");
        if (!Objects.equals(files.put(p, text), text))
            revision++;
    }

    public void remove(String p)
    {
        if (files.remove(p) != null)
        {
            revision++;
            return;
        }
        if (p.equals("/home/crew") || !p.startsWith("/home/") || files.keySet().stream().anyMatch(s -> s.startsWith(p + "/")) || directories.stream().anyMatch(s -> s.startsWith(p + "/")))
            throw new IllegalArgumentException("Directory is protected or not empty");
        if (!directories.remove(p))
            throw new IllegalArgumentException("No such file or directory");
        revision++;
    }

    public List<String> list(String p)
    {
        if (!directory(p))
        {
            if (!files.containsKey(p))
                throw new IllegalArgumentException("No such virtual path: " + p);
            return List.of(p);
        }
        String prefix = p.equals("/") ? "/" : p + "/";
        var result = new TreeSet<String>();
        for (String name : directories)
            if (!name.equals(p) && name.startsWith(prefix) && !name.substring(prefix.length()).contains("/"))
                result.add(name.substring(prefix.length()) + "/");
        for (String name : files.keySet())
            if (name.startsWith(prefix) && !name.substring(prefix.length()).contains("/"))
                result.add(name.substring(prefix.length()));
        return List.copyOf(result);
    }

    void replaceWith(VirtualFiles other)
    {
        revision++;
        files.clear();
        files.putAll(other.files);
        directories.clear();
        directories.addAll(other.directories);
    }

    void clear()
    {
        revision++;
        files.clear();
        directories.clear();
        directories.addAll(List.of("/", "/home", "/home/crew", "/etc", "/dev", "/dev/ship", "/proc"));
    }

    public Map<String, String> files()
    {
        return Map.copyOf(files);
    }

    public List<String> directories()
    {
        return List.copyOf(directories);
    }
}
