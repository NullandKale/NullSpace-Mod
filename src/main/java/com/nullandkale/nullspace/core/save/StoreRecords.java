package com.nullandkale.nullspace.core.save;

import java.util.*;

import net.minecraft.nbt.*;

/**
 * The records an operation changed in the ship store, as before and after values. A journal holds these instead of
 * two copies of the whole store. Applying the {@code before} values rolls an operation back; applying the
 * {@code after} values replays a committed one. Both are idempotent.
 *
 * <p>An entry is one of: a whole top-level value, one child of a top-level section, or one element of a list whose
 * elements are keyed by their {@code id} (ships, arrival targets, entity authority).
 */
public final class StoreRecords
{
    public enum Kind
    {
        VALUE, CHILD, KEYED
    }

    /** A null side means the entry did not exist on that side. */
    public record Entry(String top, Kind kind, String key, Tag before, Tag after)
    {
        public Entry
        {
            if (top == null || top.isBlank() || kind == null || key == null || kind != Kind.VALUE && key.isBlank())
                throw new IllegalArgumentException("Invalid journal record address");
            if (kind == Kind.KEYED)
                for (var value : new Tag[]{before, after})
                    if (value != null && (!(value instanceof CompoundTag record) || !record.getStringOr("id", "").equals(key)))
                        throw new IllegalArgumentException("Journal record identity disagrees with its key");
        }
    }

    private static Kind kindOf(Tag before, Tag after)
    {
        var sample = before != null ? before : after;
        if (before != null && after != null && before.getClass() != after.getClass())
            return Kind.VALUE;
        if (sample instanceof CompoundTag)
            return Kind.CHILD;
        if (sample instanceof ListTag && keyed(before) && keyed(after))
            return Kind.KEYED;
        return Kind.VALUE;
    }

    private static boolean keyed(Tag tag)
    {
        if (tag == null)
            return true;
        if (!(tag instanceof ListTag list))
            return false;
        var ids = new HashSet<String>();
        for (var element : list)
            if (!(element instanceof CompoundTag compound) || compound.getStringOr("id", "").isEmpty() || !ids.add(compound.getStringOr("id", "")))
                return false;
        return true;
    }

    /** The entries that differ between two stores. */
    public static List<Entry> diff(CompoundTag before, CompoundTag after)
    {
        var entries = new ArrayList<Entry>();
        var tops = new TreeSet<>(before.keySet());
        tops.addAll(after.keySet());
        for (var top : tops)
        {
            var b = before.get(top);
            var a = after.get(top);
            if (Objects.equals(a, b))
                continue;
            switch (kindOf(b, a))
            {
                case CHILD ->
                {
                    var bc = b instanceof CompoundTag c ? c : new CompoundTag();
                    var ac = a instanceof CompoundTag c ? c : new CompoundTag();
                    var keys = new TreeSet<>(bc.keySet());
                    keys.addAll(ac.keySet());
                    for (var key : keys)
                        if (!Objects.equals(bc.get(key), ac.get(key)))
                            entries.add(new Entry(top, Kind.CHILD, key, copy(bc.get(key)), copy(ac.get(key))));
                }
                case KEYED ->
                {
                    var bm = byId(b);
                    var am = byId(a);
                    var ids = new LinkedHashSet<>(bm.keySet());
                    ids.addAll(am.keySet());
                    for (var id : ids)
                        if (!Objects.equals(bm.get(id), am.get(id)))
                            entries.add(new Entry(top, Kind.KEYED, id, copy(bm.get(id)), copy(am.get(id))));
                }
                default -> entries.add(new Entry(top, Kind.VALUE, "", copy(b), copy(a)));
            }
        }
        return entries;
    }

    private static Map<String, Tag> byId(Tag tag)
    {
        var map = new LinkedHashMap<String, Tag>();
        if (tag instanceof ListTag list)
            for (var element : list)
                map.put(((CompoundTag) element).getStringOr("id", ""), element);
        return map;
    }

    private static Tag copy(Tag tag)
    {
        return tag == null ? null : tag.copy();
    }

    /** Writes one side of every entry into the store. {@code forward} chooses {@code after}; otherwise {@code before}. */
    public static void apply(CompoundTag root, List<Entry> entries, boolean forward)
    {
        for (var entry : entries)
        {
            var value = forward ? entry.after() : entry.before();
            switch (entry.kind())
            {
                case VALUE ->
                {
                    if (value == null)
                        root.remove(entry.top());
                    else
                        root.put(entry.top(), value.copy());
                }
                case CHILD ->
                {
                    var section = root.get(entry.top()) instanceof CompoundTag c ? c : null;
                    if (value == null)
                    {
                        if (section != null)
                            section.remove(entry.key());
                    }
                    else
                    {
                        if (section == null)
                        {
                            section = new CompoundTag();
                            root.put(entry.top(), section);
                        }
                        section.put(entry.key(), value.copy());
                    }
                }
                case KEYED ->
                {
                    var list = root.get(entry.top()) instanceof ListTag l ? l : null;
                    if (list == null)
                    {
                        if (value == null)
                            continue;
                        list = new ListTag();
                        root.put(entry.top(), list);
                    }
                    int index = -1;
                    for (int i = 0; i < list.size(); i++)
                        if (list.get(i) instanceof CompoundTag c && c.getStringOr("id", "").equals(entry.key()))
                            index = i;
                    if (value == null)
                    {
                        if (index >= 0)
                            list.remove(index);
                    }
                    else if (index >= 0)
                        list.set(index, value.copy());
                    else
                        list.add(value.copy());
                }
            }
        }
    }

    public static ListTag encode(List<Entry> entries)
    {
        var list = new ListTag();
        for (var entry : entries)
        {
            var tag = new CompoundTag();
            tag.putString("top", entry.top());
            tag.putString("kind", entry.kind().name());
            tag.putString("key", entry.key());
            if (entry.before() != null)
                tag.put("before", entry.before().copy());
            if (entry.after() != null)
                tag.put("after", entry.after().copy());
            list.add(tag);
        }
        return list;
    }

    public static List<Entry> decode(ListTag list)
    {
        var entries = new ArrayList<Entry>();
        for (var element : list)
        {
            var tag = (CompoundTag) element;
            entries.add(new Entry(tag.getStringOr("top", ""), Kind.valueOf(tag.getStringOr("kind", "VALUE")), tag.getStringOr("key", ""), tag.get("before"), tag.get("after")));
        }
        return entries;
    }

    /** The whole current record of every ship an operation touched, for rescuing a ship by hand. */
    public static List<String> shipIds(List<Entry> entries)
    {
        var ids = new LinkedHashSet<String>();
        for (var entry : entries)
            if (entry.top().equals("ships") && entry.kind() == Kind.KEYED)
                ids.add(entry.key());
        return List.copyOf(ids);
    }

    private StoreRecords()
    {
    }
}
