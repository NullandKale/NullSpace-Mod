package com.nullandkale.nullspace.client;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

public final class SpaceSettingsScreen extends OptionsSubScreen {
  private boolean advanced;

  public SpaceSettingsScreen(Screen parent) {
    super(parent, Minecraft.getInstance().options, Component.literal("Space Graphics"));
  }

  private void choice(Supplier<String> label, Runnable change, String help) {
    var b =
        Button.builder(
                Component.literal(label.get()),
                button -> {
                  change.run();
                  SpaceSettings.save();
                  button.setMessage(Component.literal(label.get()));
                })
            .width(310)
            .build();
    b.setTooltip(Tooltip.create(Component.literal(help)));
    list.addBig(b);
  }

  @Override
  protected void addOptions() {
    list.addHeader(Component.literal("Quality presets"));
    for (int i = 0; i < 3; i++) {
      int p = i;
      list.addBig(
          Button.builder(
                  Component.literal(
                      (SpaceSettings.matchesPreset(i) ? "> " : "")
                          + new String[] {"Fast", "Balanced", "High quality"}[i]),
                  b -> {
                    SpaceSettings.preset(p);
                    minecraft.gui.setScreen(new SpaceSettingsScreen(lastScreen));
                  })
              .width(310)
              .build());
    }
    list.addBig(
        Button.builder(
                Component.literal(advanced ? "Hide advanced graphics" : "Advanced graphics"),
                b -> {
                  var next = new SpaceSettingsScreen(lastScreen);
                  next.advanced = !advanced;
                  minecraft.gui.setScreen(next);
                })
            .width(310)
            .build());
    if (advanced) {
      list.addHeader(Component.literal("Resolution and sampling"));
      choice(
          () -> "Surface resolution: " + SpaceSettings.width() + " x " + SpaceSettings.height(),
          () -> {
            var v = SpaceSettings.get();
            v.surfaceWidth = v.surfaceWidth == 640 ? 960 : v.surfaceWidth == 960 ? 1280 : 640;
          },
          "Main sky and fullscreen feeds. Installed monitors use half this resolution.");
      choice(
          () -> "Volume resolution: " + Math.round(SpaceSettings.get().volumeScale * 100) + "%",
          () -> {
            var v = SpaceSettings.get();
            v.volumeScale =
                v.volumeScale < .25 ? .25 : v.volumeScale < .5 ? .5 : v.volumeScale < 1 ? 1 : .125;
          },
          "Higher values remove coarse nebula edges and cost more GPU time and memory.");
      choice(
          () -> "Volume integration quality: " + SpaceSettings.get().volumeSteps,
          () -> {
            var v = SpaceSettings.get();
            v.volumeSteps = v.volumeSteps >= 32 ? 8 : v.volumeSteps + 8;
          },
          "Samples per intersected depth interval. More samples reduce banding.");
      choice(
          () -> "Surface antialiasing: " + SpaceSettings.get().surfaceSamples + " samples",
          () -> {
            var v = SpaceSettings.get();
            v.surfaceSamples = v.surfaceSamples == 1 ? 4 : 1;
          },
          "Spatial multisampling smooths planet silhouettes without animated subpixel jitter.");
      choice(
          () -> "Temporal reconstruction: " + (SpaceSettings.get().temporal ? "On" : "Off"),
          () -> SpaceSettings.get().temporal = !SpaceSettings.get().temporal,
          "Reprojects matching pixels and rejects camera cuts and occlusion changes.");
      choice(
          () -> "Stochastic volume sampling: " + (SpaceSettings.get().stochastic ? "On" : "Off"),
          () -> SpaceSettings.get().stochastic = !SpaceSettings.get().stochastic,
          "Optional animated integration samples. Off keeps clouds stable.");
    }
    choice(
        () -> "Planet space skies: " + (SpaceSettings.get().planetSky ? "On" : "Off"),
        () -> SpaceSettings.get().planetSky = !SpaceSettings.get().planetSky,
        "Use the local solar-system definition behind planetary terrain.");
    choice(
        () -> "Air flow particles: " + (SpaceSettings.get().airParticles ? "On" : "Off"),
        () -> SpaceSettings.get().airParticles = !SpaceSettings.get().airParticles,
        "Visualizes active filling, transfer and escaping air.");
    choice(
        () -> "Clear-weather clouds: " + (SpaceSettings.get().clouds ? "On" : "Off"),
        () -> SpaceSettings.get().clouds = !SpaceSettings.get().clouds,
        "Clear-weather clouds. Rain and thunder always show overcast clouds.");
    choice(
        () -> "Cloud quality: " + SpaceSettings.get().cloudSteps + " steps",
        () -> {
          var v = SpaceSettings.get();
          v.cloudSteps = v.cloudSteps >= 64 ? 16 : v.cloudSteps + 16;
        },
        "Stable spatial integration. Higher values improve cloud silhouettes and internal detail.");
  }
}
