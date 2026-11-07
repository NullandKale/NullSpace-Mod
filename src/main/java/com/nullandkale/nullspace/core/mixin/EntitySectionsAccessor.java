package com.nullandkale.nullspace.core.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PersistentEntitySectionManager.class)
public interface EntitySectionsAccessor {
  @Accessor("sectionStorage")
  EntitySectionStorage<Entity> nullspace$sections();
}
