package com.nullandkale.nullspace.model.computer;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.Arrays;

import net.minecraft.nbt.*;

/** Versioned disk envelope. Large images must never pass through NBT's writeUTF fallback. */
public final class ComputerImageCodec
{
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    public static CompoundTag encode(String image)
    {
        byte[] bytes;
        try
        {
            var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(image));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        }
        catch (CharacterCodingException invalid)
        {
            throw new IllegalArgumentException("Invalid computer image Unicode", invalid);
        }
        if (bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Computer image exceeds byte budget");
        var tag = new CompoundTag();
        tag.putInt("version", 1);
        tag.putByteArray("utf8", bytes);
        tag.putByteArray("sha256", digest(bytes));
        return tag;
    }

    public static String decode(Tag image)
    {
        if (image == null)
            return "";
        if (!(image instanceof CompoundTag tag) || tag.getIntOr("version", -1) != 1)
            throw new IllegalArgumentException("Unsupported computer image envelope");
        byte[] bytes = tag.getByteArray("utf8").orElseThrow(() -> new IllegalArgumentException("Missing computer image bytes"));
        if (bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Computer image exceeds byte budget");
        if (!Arrays.equals(digest(bytes), tag.getByteArray("sha256").orElse(new byte[0])))
            throw new IllegalArgumentException("Computer image checksum mismatch");
        try
        {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }
        catch (CharacterCodingException invalid)
        {
            throw new IllegalArgumentException("Invalid computer image UTF-8", invalid);
        }
    }

    private static byte[] digest(byte[] bytes)
    {
        try
        {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        }
        catch (NoSuchAlgorithmException impossible)
        {
            throw new AssertionError(impossible);
        }
    }

    private ComputerImageCodec()
    {
    }
}
