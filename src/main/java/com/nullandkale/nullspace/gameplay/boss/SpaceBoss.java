package com.nullandkale.nullspace.gameplay.boss;

import java.util.List;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.level.Level;

/**
 * The end-game boss and what it gives (chunk A8 of {@code docs/design/rewards-work-plan.md}): the <b>space wither</b>, a white wither
 * at the chart's pulsar or black hole ({@link SpaceNests}); the <b>black hole</b> it drops; and the <b>beacon helmet</b> made with
 * it, a suit helmet that gives the wearer a beacon's effects. Sneak-use the helmet to change them.
 */
public final class SpaceBoss
{
    public static final ResourceKey<EntityType<?>> SPACE_WITHER_KEY = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse("nullspace:space_wither"));

    public static final EntityType<SpaceWither> SPACE_WITHER = Registry.register(BuiltInRegistries.ENTITY_TYPE, SPACE_WITHER_KEY, EntityType.Builder.of(SpaceWither::new, MobCategory.MONSTER).fireImmune().sized(0.9F, 3.5F).clientTrackingRange(10).build(SPACE_WITHER_KEY));

    /** Dropped by the space wither and by nothing else. */
    public static final Item BLACK_HOLE = Registry.register(BuiltInRegistries.ITEM, Identifier.parse("nullspace:black_hole"), new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.parse("nullspace:black_hole"))).rarity(Rarity.EPIC)));

    private static final ArmorMaterial BEACON_SUIT = new ArmorMaterial(25, java.util.Map.of(ArmorType.BOOTS, 2, ArmorType.LEGGINGS, 5, ArmorType.CHESTPLATE, 6, ArmorType.HELMET, 3, ArmorType.BODY, 5), 9, net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_NETHERITE, 1, 0, net.minecraft.tags.ItemTags.REPAIRS_IRON_ARMOR, ResourceKey.create(EquipmentAssets.ROOT_ID, Identifier.parse("nullspace:armored_space_suit")));

    /** A suit helmet of green gems with a black hole in it: it also lets its wearer breathe, as the suit's helmets do. */
    public static final Item BEACON_HELMET = Registry.register(BuiltInRegistries.ITEM, Identifier.parse("nullspace:beacon_helmet"), new BeaconHelmet(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.parse("nullspace:beacon_helmet"))).humanoidArmor(BEACON_SUIT, ArmorType.HELMET).rarity(Rarity.EPIC).stacksTo(1)));

    /** The beacon's primary effects, in the order the helmet cycles through them. */
    private static final List<Holder<MobEffect>> EFFECTS = List.of(MobEffects.SPEED, MobEffects.HASTE, MobEffects.RESISTANCE, MobEffects.JUMP_BOOST, MobEffects.STRENGTH);

    private static final List<String> NAMES = List.of("Speed", "Haste", "Resistance", "Jump Boost", "Strength");

    /** How often the effects are renewed, and how long each renewal lasts, in ticks. */
    public static final int RENEW = 80, LAST = 220;

    public static void initialize()
    {
        FabricDefaultAttributeRegistry.register(SPACE_WITHER, WitherBoss.createAttributes());
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server ->
        {
            if (server.getTickCount() % RENEW == 0)
                for (var player : server.getPlayerList().getPlayers())
                    renew(player);
            SpaceNests.tick(server);
        });
    }

    /** The helmet's setting, 0 to 9: the effect (0 to 4) at level II, or (5 to 9) the same effect at level I with Regeneration. */
    public static int setting(ItemStack helmet)
    {
        return helmet.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getIntOr("beacon", 0) % (EFFECTS.size() * 2);
    }

    public static ItemStack withSetting(ItemStack helmet, int setting)
    {
        var tag = helmet.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt("beacon", Math.floorMod(setting, EFFECTS.size() * 2));
        helmet.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return helmet;
    }

    public static String describe(int setting)
    {
        return NAMES.get(setting % EFFECTS.size()) + (setting >= EFFECTS.size() ? " I with Regeneration" : " II");
    }

    /** Gives a player in the helmet its effects. */
    public static void renew(ServerPlayer player)
    {
        var helmet = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
        if (!helmet.is(BEACON_HELMET) || player.isSpectator())
            return;
        int setting = setting(helmet);
        boolean regeneration = setting >= EFFECTS.size();
        player.addEffect(new MobEffectInstance(EFFECTS.get(setting % EFFECTS.size()), LAST, regeneration ? 0 : 1, true, true));
        if (regeneration)
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, LAST, 0, true, true));
    }

    private static final class BeaconHelmet extends Item
    {
        BeaconHelmet(Properties properties)
        {
            super(properties);
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand)
        {
            var stack = player.getItemInHand(hand);
            if (!player.isShiftKeyDown())
                return super.use(level, player, hand);
            if (!level.isClientSide())
            {
                int next = setting(stack) + 1;
                withSetting(stack, next);
                player.sendOverlayMessage(Component.literal("Beacon helmet: " + describe(next % (EFFECTS.size() * 2))));
            }
            return InteractionResult.SUCCESS;
        }
    }

    private SpaceBoss()
    {
    }
}
