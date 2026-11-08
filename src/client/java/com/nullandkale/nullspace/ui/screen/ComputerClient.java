package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.gameplay.computer.ComputerService;
import com.nullandkale.nullspace.ui.terminal.StreamTerminal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ComputerClient
{
    private record Pending(UUID session, String text)
    {}

    private static final java.util.ArrayDeque<Pending> PENDING = new java.util.ArrayDeque<>();
    private static UUID session;
    private static StreamTerminal device;
    private static TerminalScreen screen;
    private static int ticks;

    public static void clear()
    {
        session = null;
        device = null;
        screen = null;
        ticks = 0;
        PENDING.clear();
    }

    public static void initialize()
    {
        ClientPlayNetworking.registerGlobalReceiver(ComputerService.Screen.TYPE, (p, c) -> c.client().execute(() ->
        {
            if (p.open())
            {
                session = p.session();
                PENDING.clear();
                UUID id = session;
                device = new StreamTerminal(bytes ->
                {
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (text.length() > 32768)
                        return;
                    int queued = PENDING.stream().mapToInt(q -> q.text.length()).sum();
                    if (queued + text.length() > 32768)
                        return;
                    for (int at = 0; at < text.length(); at += 256)
                        PENDING.addLast(new Pending(id, text.substring(at, Math.min(text.length(), at + 256))));
                });
                screen = new TerminalScreen(device, () ->
                {
                    if (!PENDING.isEmpty() && c.client().getConnection() != null)
                    {
                        var key = PENDING.removeFirst();
                        var batch = new StringBuilder(key.text);
                        while (!PENDING.isEmpty() && PENDING.getFirst().session.equals(key.session) && batch.length() + PENDING.getFirst().text.length() <= 256)
                            batch.append(PENDING.removeFirst().text);
                        ClientPlayNetworking.send(new ComputerService.Input(key.session, batch.toString(), false));
                    }
                    if (++ticks % 20 == 0 && c.client().getConnection() != null)
                        ClientPlayNetworking.send(new ComputerService.Input(id, "\0", false));
                }, () ->
                {
                    PENDING.removeIf(q -> q.session.equals(id));
                    if (c.client().getConnection() != null)
                        ClientPlayNetworking.send(new ComputerService.Input(id, "", true));
                });
                c.client().gui.setScreen(screen);
                device.write(p.text());
            }
            else if (p.session().equals(session) && c.client().gui.screen() == screen)
            {
                if (p.text().isEmpty())
                    c.client().gui.setScreen(null);
                else
                    device.write(p.text());
            }
        }));
    }

    private ComputerClient()
    {
    }
}
