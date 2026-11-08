package com.nullandkale.nullspace.model.optics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeVolumeShaderTest
{
    private static final String SOURCE = "#version 330\n" + "layout(location=0) out vec4 _frag_output_00;\n" + "void main(){_frag_output_00=vec4(1.0);}";

    @Test
    void unrelatedShadersAndNonColorPassesRemainIdentical()
    {
        assertEquals(SOURCE, NativeVolumeShader.patch(SOURCE, "gui", "gui"));
        String depth = "#version 330\nlayout(location=0) out float _frag_output_00;void main(){}";
        assertEquals(depth, NativeVolumeShader.patch(depth, "terrain", "depth"));
    }

    @Test
    void nativeBodyAndAlphaAreRetainedAndWrappingIsIdempotent()
    {
        var result = NativeVolumeShader.patch(SOURCE, "minecraft:terrain", "translucent");
        assertTrue(result.contains("void nullspace_original_main(){_frag_output_00=vec4(1.0);}"));
        assertTrue(result.contains("NullSpaceVolumeEnabled"));
        assertTrue(result.contains("float weight=false?"));
        assertEquals(result, NativeVolumeShader.patch(result, "terrain", "translucent"));
    }

    @Test
    void accumulationUsesPremultipliedOptics()
    {
        assertTrue(NativeVolumeShader.patch(SOURCE, "terrain", "oit_accumulate").contains("float weight=true?"));
        assertTrue(NativeVolumeShader.patch(SOURCE, "entity", "entity_translucent").contains("float weight=false?"));
    }

    @Test
    void nativeAdapterUsesTheReloadedOpticsSource()
    {
        try
        {
            NativeVolumeShader.source(() -> "// resource pack optics generation 2");
            assertTrue(NativeVolumeShader.patch(SOURCE, "terrain", "solid").contains("resource pack optics generation 2"));
        }
        finally
        {
            NativeVolumeShader.resetSource();
        }
    }
}
