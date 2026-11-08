package com.nullandkale.nullspace.core;

import com.nullandkale.nullspace.content.guide.FieldGuide;
import com.nullandkale.nullspace.content.guide.GuideRecipes;
import com.nullandkale.nullspace.dev.RecipeLab;
import com.nullandkale.nullspace.model.optics.NativeVolumeShader;
import com.nullandkale.nullspace.model.space.SpaceDefinition;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;
import com.nullandkale.nullspace.render.compute.ComputeImages;
import com.nullandkale.nullspace.render.compute.ComputeProgram;
import com.nullandkale.nullspace.render.compute.ComputeStateScope;
import com.nullandkale.nullspace.render.compute.SpaceComputeRenderer;
import com.nullandkale.nullspace.render.scene.SceneViewport;
import com.nullandkale.nullspace.testing.ClientTestEvidence;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.server.packs.PackType;

import com.mojang.renderpearl.backend.opengl.GlTexture;

import static org.lwjgl.opengl.GL43C.*;

/** An installed on-disk pack changes actual rendered pixels in an already allocated viewport. */
public final class ResourceReloadClientGameTest implements FabricClientGameTest
{
    private static void check(boolean condition, String message)
    {
        if (!condition)
            throw new AssertionError(message);
    }

    private static void write(Path file, String text)
    {
        try
        {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void reload(ClientGameTestContext context)
    {
        var future = new AtomicReference<CompletableFuture<Void>>();
        context.runOnClient(client -> future.set(client.reloadResourcePacks()));
        context.waitFor(client -> future.get().isDone(), 6000);
        context.runOnClient(client -> future.get().join());
    }

    private static CompoundTag scene()
    {
        var scene = new CompoundTag();
        scene.putBoolean("universe", true);
        scene.putString("observer", "reload-test");
        scene.put("catalog", SpaceDefinitionCodec.encode(new SpaceDefinition("test:reload", 1, 42, 1, 0, List.of(), List.of(), List.of())));
        var subjects = new ListTag();
        var subject = new CompoundTag();
        subject.putString("id", "reload-test");
        subject.putIntArray("blocks", new int[32768]);
        subjects.add(subject);
        scene.put("subjects", subjects);
        scene.putLong("receivedNanos", Long.MAX_VALUE);
        return scene;
    }

    private static float[] pixel(SceneViewport view)
    {
        view.render(scene(), 0, 0, 70);
        int row = glGetInteger(GL_PACK_ROW_LENGTH), skip = glGetInteger(GL_PACK_SKIP_ROWS);
        float[] result;
        try
        {
            glPixelStorei(GL_PACK_ROW_LENGTH, 137);
            glPixelStorei(GL_PACK_SKIP_ROWS, 3);
            result = ComputeImages.inspectTexture(((GlTexture) view.target.getColorTexture()).glId(), 33, 25);
            check(glGetInteger(GL_PACK_ROW_LENGTH) == 137 && glGetInteger(GL_PACK_SKIP_ROWS) == 3, "Diagnostic readback restores inherited pixel-pack state");
        }
        finally
        {
            glPixelStorei(GL_PACK_ROW_LENGTH, row);
            glPixelStorei(GL_PACK_SKIP_ROWS, skip);
        }
        int p = (12 * 33 + 16) * 4;
        return Arrays.copyOfRange(result, p, p + 4);
    }

    private static void nativePixelUsesPack()
    {
        String source = NativeVolumeShader.patch("#version 430\n" + "layout(location=0) out vec4 _frag_output_00;\n" + "void main(){_frag_output_00=vec4(1);}", "terrain", "solid");
        check(source.contains("NULLSPACE_TEST_PACK"), "Native adapter must read the installed resource pack");
        source = source.replace("layout(location=0) out vec4 _frag_output_00;", "vec4 _frag_output_00;").replace("gl_FragCoord", "vec4(.5,.5,0.,1.)").replace("void main(){", "void native_main(){");
        source += "\n" + "layout(local_size_x=1) in;layout(std430,binding=0) buffer Output{vec4 pixel;};void" + " main(){native_main();pixel=_frag_output_00;}";
        int buffer = glGenBuffers();
        try (var state = new ComputeStateScope(); var program = new ComputeProgram(source))
        {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            glBufferData(GL_SHADER_STORAGE_BUFFER, 16, GL_DYNAMIC_READ);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer);
            program.use();
            program.scalar("NullSpaceVolumeEnabled", 1);
            program.matrix("NullSpaceInverseView", new org.joml.Matrix4f());
            glUniform4f(program.uniform("NullSpaceVolumeInfo"), 1, 1, 1, 24);
            program.dispatch(1, 1, 1, 1);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            float[] result = new float[4];
            glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, result);
            check(result[0] < .01 && result[1] > .7 && result[2] < .01, "Native volume adapter executes the pack's green optics on the GPU");
        }
        finally
        {
            glDeleteBuffers(buffer);
        }
    }

    @Override
    public void runTest(ClientGameTestContext context)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        var directory = new AtomicReference<Path>();
        var selected = new AtomicReference<List<String>>();
        var viewport = new SceneViewport[1];
        var before = new float[1][];
        try (var world = context.worldBuilder().create())
        {
            world.getConnection().waitForChunksRender();
            var dataReload = new AtomicReference<CompletableFuture<Void>>();
            var book = new net.minecraft.world.item.ItemStack(FieldGuide.ITEM);
            world.getServer().runOnServer(server ->
            {
                FieldGuide.refresh(book, server.overworld());
                var root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR).resolve("nullspace-guide-regression");
                var format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
                write(root.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Disposable recipe override\",\"min_format\":[" + format.major() + "," + format.minor() + "],\"max_format\":[" + format.major() + "," + format.minor() + "]}}");
                write(root.resolve("data/nullspace/recipe/computer_base.json"), """
                    {"type":"minecraft:crafting_shaped","pattern":["C C"," R ","C C"],
                     "key":{"C":"#nullspace:test_conductor","R":"minecraft:redstone_torch"},
                     "result":{"id":"nullspace:computer_base","count":3}}
                    """);
                write(root.resolve("data/nullspace/tags/item/test_conductor.json"), "{\"values\":[\"minecraft:iron_ingot\",\"minecraft:copper_ingot\"]}");
                server.getPackRepository().reload();
                var packs = new ArrayList<>(server.getPackRepository().getSelectedIds());
                packs.add("file/nullspace-guide-regression");
                dataReload.set(server.reloadResources(packs));
            });
            context.waitFor(client -> dataReload.get().isDone(), 6000);
            world.getServer().runOnServer(server ->
            {
                dataReload.get().join();
                var recipe = RecipeLab.recipe(server, "computer_base");
                var cells = java.util.stream.IntStream.range(0, 9).mapToObj(i -> switch (i)
                {
                    case 0, 2, 6, 8 -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COPPER_INGOT);
                    case 4 -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.REDSTONE_TORCH);
                    default -> net.minecraft.world.item.ItemStack.EMPTY;
                }).toList();
                var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, cells);
                check(recipe.matches(input, server.overworld()) && recipe.assemble(input).getCount() == 3, "Overridden recipe must actually craft the new output");
                FieldGuide.refresh(book, server.overworld());
                var page = book.get(GuideRecipes.COMPONENT).recipes().stream().filter(r -> r.name().equals("computer_base")).findFirst().orElseThrow();
                check(page.count() == 3 && page.cells().get(0).containsAll(List.of("minecraft:iron_ingot", "minecraft:copper_ingot")) && page.cells().get(4).equals(List.of("minecraft:redstone_torch")) && page.cells().get(1).isEmpty(), "An existing guide refreshes icon positions, tag alternatives and output count" + " from the reloaded datapack");
            });
            try
            {
                context.runOnClient(client ->
                {
                    directory.set(client.getResourcePackDirectory().resolve("nullspace-reload-regression-" + UUID.randomUUID()));
                    selected.set(List.copyOf(client.getResourcePackRepository().getSelectedIds()));
                    var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
                    write(directory.get().resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Disposable reload regression\",\"min_format\":[" + format.major() + "," + format.minor() + "],\"max_format\":[" + format.major() + "," + format.minor() + "]}}");
                    write(directory.get().resolve("assets/nullspace/shaders/include/space_optics.glsl"), """
                        // NULLSPACE_TEST_PACK
                        float spaceOpaqueDistance(sampler2D depth,mat4 inverseView,vec2 uv,bool zeroOne){return 1000000.;}
                        vec4 spaceOptics(sampler2D prefix,sampler2D depth,mat4 inverseView,vec2 uv,float wanted,int slices,bool zeroOne){return vec4(0.,1.,0.,0.);}
                        """);
                    viewport[0] = new SceneViewport(33, 25);
                    before[0] = pixel(viewport[0]);
                    client.getResourcePackRepository().reload();
                    check(client.getResourcePackRepository().addPack("file/" + directory.get().getFileName()), "Disposable resource pack must be discovered and selected");
                });
                reload(context);
                context.runOnClient(client ->
                {
                    var result = pixel(viewport[0]);
                    check(result[0] < .01 && result[1] > .7 && result[2] < .01 && Math.abs(result[1] - before[0][1]) > .2, "Existing viewport must render changed pixels after a real resource-pack reload");
                    nativePixelUsesPack();
                    write(directory.get().resolve("assets/nullspace/shaders/compute/surface.comp"), "#version 430\nintentionally_invalid_compute_shader");
                });
                reload(context);
                context.runOnClient(client ->
                {
                    var result = pixel(viewport[0]);
                    check(result[1] > .7 && result[0] < .01, "A broken compute pack retains working programs and the viewport stays usable");
                    check(!SpaceComputeRenderer.reload(), "The installed broken compute shader must actually fail compilation");
                });
            }
            finally
            {
                context.runOnClient(client ->
                {
                    if (selected.get() != null)
                        client.getResourcePackRepository().setSelected(selected.get());
                });
                reload(context);
                context.runOnClient(client ->
                {
                    if (viewport[0] != null)
                    {
                        try
                        {
                            check(pixel(viewport[0])[1] < .5, "Removing the override restores normal optics in the same viewport");
                        }
                        finally
                        {
                            viewport[0].close();
                        }
                    }
                });
                if (directory.get() != null)
                    try (var paths = Files.walk(directory.get()))
                    {
                        check(directory.get().getFileName().toString().startsWith("nullspace-reload-regression-"), "Only the disposable test pack may be removed");
                        for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                            Files.delete(path);
                    }
                    catch (java.io.IOException e)
                    {
                        throw new java.io.UncheckedIOException(e);
                    }
            }
        }
        ClientTestEvidence.passed(getClass());
    }
}
