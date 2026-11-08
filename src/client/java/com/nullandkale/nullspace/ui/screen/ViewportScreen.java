package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.gameplay.scene.ScenePackets;
import com.nullandkale.nullspace.render.SpaceSettings;
import com.nullandkale.nullspace.render.scene.SceneViewport;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.*;

public final class ViewportScreen extends Screen implements SceneScreen
{
    private final TerminalScreen parent;
    private ScenePackets.Frame frame;
    private SceneViewport viewport;
    private int ticks, selection, lastUpdate;
    private float yaw, pitch, fov = 70;

    public ViewportScreen(ScenePackets.Frame frame)
    {
        super(Component.literal("Remote viewport"));
        parent = net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof TerminalScreen t ? t : null;
        this.frame = frame;
        this.selection = frame.scene().getIntOr("selection", 0);
    }

    public boolean owns(java.util.UUID id)
    {
        return frame.lease().equals(id);
    }

    public String status()
    {
        return frame.scene().getStringOr("status", "");
    }

    public net.minecraft.core.BlockPos sourcePosition()
    {
        return net.minecraft.core.BlockPos.of(frame.scene().getLongOr("source", 0));
    }

    public void update(ScenePackets.Frame next)
    {
        if (frame.lease().equals(next.lease()) && next.revision() > frame.revision())
        {
            frame = next;
            selection = next.scene().getIntOr("selection", selection);
            lastUpdate = ticks;
        }
    }

    public SceneViewport viewport()
    {
        return viewport;
    }

    public void renderFrame()
    {
        resizeViewport();
        if (viewport != null)
        {
            if (ticks - lastUpdate > 60)
                viewport.clear();
            else
                viewport.render(frame.scene(), yaw, pitch, fov);
        }
    }

    @Override
    protected void init()
    {
        if (!frame.scene().getBooleanOr("fixedCamera", false))
        {
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Previous camera"), b -> changeCamera(-1)).bounds(8, height - 24, 96, 18).build());
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Next camera"), b -> changeCamera(1)).bounds(width - 104, height - 24, 96, 18).build());
        }
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal(frame.scene().getBooleanOr("returnMachine", false) ? "Back" : "Close"), b -> onClose()).bounds(width - 62, 4, 54, 18).build());
        resizeViewport();
    }

    private void changeCamera(int step)
    {
        selection = Math.clamp(selection + step, -10000, 10000);
        ClientPlayNetworking.send(new ScenePackets.KeepAlive(frame.lease(), false, selection));
    }

    private void resizeViewport()
    {
        int w = SpaceSettings.cameraWidth();
        int h = Math.max(16, (int) Math.round(w * Math.max(1, height) / (double) Math.max(1, width)));
        if (viewport == null || viewport.target.width != w || viewport.target.height != h)
        {
            if (viewport != null)
                viewport.close();
            viewport = new SceneViewport(w, h);
        }
    }

    @Override
    public void tick()
    {
        if (++ticks % 20 == 0)
            ClientPlayNetworking.send(new ScenePackets.KeepAlive(frame.lease(), false, selection));
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    @Override
    public void onClose()
    {
        if (parent != null)
            TerminalScreen.rememberReturn(parent);
        super.onClose();
    }

    @Override
    public void removed()
    {
        if (minecraft.getConnection() != null)
            ClientPlayNetworking.send(new ScenePackets.KeepAlive(frame.lease(), true, selection));
        if (viewport != null)
        {
            viewport.close();
            viewport = null;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        switch (key.key())
        {
            case InputConstants.KEY_LEFT -> yaw -= 5;
            case InputConstants.KEY_RIGHT -> yaw += 5;
            case InputConstants.KEY_UP -> pitch -= 5;
            case InputConstants.KEY_DOWN -> pitch += 5;
            case InputConstants.KEY_PAGEUP -> fov = Math.max(20, fov - 5);
            case InputConstants.KEY_PAGEDOWN -> fov = Math.min(110, fov + 5);
            case InputConstants.KEY_TAB ->
            {
                if (!frame.scene().getBooleanOr("fixedCamera", false))
                {
                    changeCamera(1);
                }
            }
            case InputConstants.KEY_F2 -> net.minecraft.client.Screenshot.takeScreenshot(viewport.target, image ->
            {
                try
                {
                    var path = minecraft.gameDirectory.toPath().resolve("screenshots");
                    java.nio.file.Files.createDirectories(path);
                    image.writeToFile(path.resolve("viewport-" + System.currentTimeMillis() + ".png"));
                }
                catch (java.io.IOException e)
                {
                    throw new java.io.UncheckedIOException(e);
                }
                finally
                {
                    image.close();
                }
            });
            default ->
            {
                return super.keyPressed(key);
            }
        }
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta)
    {
        graphics.fill(0, 0, width, height, TerminalTheme.BACKGROUND);
        var sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false);
        if (viewport != null)
            graphics.blit(viewport.target.getColorTextureView(), sampler, 0, 0, width, height, 0, 1, 1, 0);
        graphics.fill(0, 0, width, 26, 0xc0101823);
        int footerHeight = frame.scene().getBooleanOr("fixedCamera", false) ? 18 : 42;
        graphics.fill(0, height - footerHeight, width, height, 0xa0101823);
        String status = ticks - lastUpdate > 60 ? "STALE - source connection lost" : frame.scene().getStringOr("status", "Loading");
        int color = TerminalTheme.feedStatus(status);
        graphics.fill(0, 0, 3, 26, color);
        graphics.text(font, font.plainSubstrByWidth(status, Math.max(0, width - 80)), 8, 9, color, false);
        graphics.text(font, font.plainSubstrByWidth("Arrows aim | Wheel zoom | F2 snapshot | Esc back", width - 16), 8, height - footerHeight + 4, TerminalTheme.MUTED, false);
        super.extractRenderState(graphics, mx, my, delta);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy)
    {
        fov = Math.clamp(fov - (float) sy * 5, 20, 110);
        return true;
    }
}
