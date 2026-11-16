package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.render.SpaceSettings;

import java.util.List;
import java.util.function.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * The Space Graphics settings, laid out like the game's own video settings: sections, two columns, sliders for anything
 * with a range and plain toggles for on and off. Every change is applied and saved as it is made.
 */
public final class SpaceSettingsScreen extends OptionsSubScreen
{
    private static final List<Integer> SURFACE_WIDTHS = List.of(640, 960, 1280);
    private static final List<Integer> CAMERA_WIDTHS = List.of(640, 960, 1280, 1920, 2560);
    private static final List<Double> VOLUME_SCALES = List.of(.125, .25, .5, 1.);
    private static final List<Integer> VOLUME_STEPS = List.of(8, 16, 24, 32);
    private static final List<Integer> CLOUD_STEPS = List.of(16, 32, 48, 64);

    private final double scroll;

    public SpaceSettingsScreen(Screen parent)
    {
        this(parent, 0);
    }

    private SpaceSettingsScreen(Screen parent, double scroll)
    {
        super(parent, Minecraft.getInstance().options, Component.literal("Space Graphics"));
        this.scroll = scroll;
    }

    /** Shows the screen again with every control read from the settings, at the same scroll position. */
    private void reopen()
    {
        minecraft.gui.setScreen(new SpaceSettingsScreen(lastScreen, list.scrollAmount()));
    }

    @Override
    protected void init()
    {
        super.init();
        list.setScrollAmount(scroll);
    }

    private static OptionInstance.TooltipSupplier<Integer> tip(String help)
    {
        return OptionInstance.cachedConstantTooltip(Component.literal(help));
    }

    /** A slider over whole numbers; the value is applied and saved when the slider is released. */
    private static OptionInstance<Integer> slider(String caption, String help, int min, int max, int current, IntConsumer set, IntFunction<String> text)
    {
        return new OptionInstance<>(caption, tip(help), (c, v) -> Component.literal(caption + ": " + text.apply(v)), new OptionInstance.IntRange(min, max, false), Math.clamp(current, min, max), v ->
        {
            set.accept(v);
            SpaceSettings.save();
        });
    }

    /** A slider that steps through a fixed list of values. */
    private static <T> OptionInstance<Integer> steps(String caption, String help, List<T> values, T current, Consumer<T> set, Function<T, String> text)
    {
        int index = 0;
        for (int i = 0; i < values.size(); i++)
            if (values.get(i).equals(current))
                index = i;
        return slider(caption, help, 0, values.size() - 1, index, i -> set.accept(values.get(i)), i -> text.apply(values.get(i)));
    }

    private static OptionInstance<Boolean> toggle(String caption, String help, boolean current, Consumer<Boolean> set)
    {
        return OptionInstance.createBoolean(caption, OptionInstance.cachedConstantTooltip(Component.literal(help)), current, v ->
        {
            set.accept(v);
            SpaceSettings.save();
        });
    }

    private static String percent(double value)
    {
        return Math.round(value * 100) + "%";
    }

    private Button preset(int index)
    {
        String name = new String[]{"Fast", "Balanced", "High quality"}[index];
        var b = Button.builder(Component.literal((SpaceSettings.matchesPreset(index) ? "> " : "") + name), button ->
        {
            SpaceSettings.preset(index);
            reopen();
        }).build();
        b.setTooltip(Tooltip.create(Component.literal("Sets resolution, sampling and cloud quality together. How the clouds look is left as you set it.")));
        return b;
    }

    @Override
    protected void addOptions()
    {
        var v = SpaceSettings.get();
        list.addHeader(Component.literal("Quality presets"));
        list.addSmall(preset(0), preset(1));
        var reset = Button.builder(Component.literal("Reset all to defaults"), button ->
        {
            SpaceSettings.defaults();
            reopen();
        }).build();
        reset.setTooltip(Tooltip.create(Component.literal("Puts every space graphics setting, including the cloud look, back to its default.")));
        list.addSmall(preset(2), reset);

        list.addHeader(Component.literal("Clouds"));
        list.addSmall(toggle("Clear-weather clouds", "Clouds in clear weather. Rain and thunder always show overcast clouds.", v.clouds, on -> SpaceSettings.get().clouds = on), toggle("Cloud shadows", "Clouds cast shadows through the air. Off is faster.", v.cloudShadows, on -> SpaceSettings.get().cloudShadows = on));
        list.addSmall(steps("Cloud quality", "Samples through the cloud layer. Higher values improve cloud silhouettes and inner detail.", CLOUD_STEPS, v.cloudSteps, n -> SpaceSettings.get().cloudSteps = n, n -> n + " steps"), slider("Coverage", "How much of the sky is cloud in clear weather, added to the planet's own. Rain is always overcast.", -50, 50, (int) Math.round(v.cloudCoverage * 100), n -> SpaceSettings.get().cloudCoverage = n / 100., n -> (n > 0 ? "+" : "") + n + "%"));
        list.addSmall(slider("Density", "How thick the clouds are. Thicker clouds block more light and cast darker shadows.", 25, 200, (int) Math.round(v.cloudDensity * 100), n -> SpaceSettings.get().cloudDensity = n / 100., n -> n + "%"), slider("Wind", "How fast the clouds drift. Zero holds them still.", 0, 300, (int) Math.round(v.cloudWind * 100), n -> SpaceSettings.get().cloudWind = n / 100., n -> n + "%"));
        list.addBig(slider("Block size", "How big the cloud blocks are. Small is fine and soft, large is broad and blocky.", 50, 200, (int) Math.round(v.cloudScale * 100), n -> SpaceSettings.get().cloudScale = n / 100., n -> n + "%"));

        list.addHeader(Component.literal("Effects"));
        list.addSmall(toggle("Planet space skies", "Use the local solar-system definition behind planetary terrain.", v.planetSky, on -> SpaceSettings.get().planetSky = on), toggle("Air flow particles", "Shows active filling, transfer and escaping air.", v.airParticles, on -> SpaceSettings.get().airParticles = on));

        list.addHeader(Component.literal("Resolution"));
        list.addSmall(steps("Surface", "Main sky and exterior ship views. Camera feeds have their own setting.", SURFACE_WIDTHS, v.surfaceWidth, n -> SpaceSettings.get().surfaceWidth = n, n -> n + " x " + n * 9 / 16), steps("Cameras", "Camera and monitor feeds. Fullscreen keeps your display's proportions. Higher uses more GPU memory.", CAMERA_WIDTHS, v.cameraWidth, n -> SpaceSettings.get().cameraWidth = n, n -> n + " wide"));
        list.addSmall(steps("Volumes", "Nebula and gas resolution. Higher values remove coarse edges and cost more GPU time and memory.", VOLUME_SCALES, v.volumeScale, d -> SpaceSettings.get().volumeScale = d, SpaceSettingsScreen::percent), steps("Volume quality", "Samples per intersected depth interval. More samples reduce banding.", VOLUME_STEPS, v.volumeSteps, n -> SpaceSettings.get().volumeSteps = n, n -> n + " steps"));

        list.addHeader(Component.literal("Sampling"));
        list.addSmall(OptionInstance.createBoolean("Antialiasing", OptionInstance.cachedConstantTooltip(Component.literal("Spatial multisampling smooths planet silhouettes without animated subpixel jitter.")), (c, on) -> Component.literal(on ? "4 samples" : "Off"), v.surfaceSamples >= 4, on ->
        {
            SpaceSettings.get().surfaceSamples = on ? 4 : 1;
            SpaceSettings.save();
        }), toggle("Temporal reconstruction", "Reprojects matching pixels and rejects camera cuts and occlusion changes.", v.temporal, on -> SpaceSettings.get().temporal = on));
        list.addBig(toggle("Stochastic volume sampling", "Optional animated integration samples. Off keeps clouds and nebulae stable.", v.stochastic, on -> SpaceSettings.get().stochastic = on));
    }
}
