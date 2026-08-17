package dev.wiktor.pixelbuilding;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

public final class ModContent {
    public static final Identifier MICRO_CONTAINER_ID = PixelBuilding.id("micro_container");
    public static final ResourceKey<Block> MICRO_CONTAINER_KEY = ResourceKey.create(Registries.BLOCK, MICRO_CONTAINER_ID);

    public static final MicroContainerBlock MICRO_CONTAINER = Registry.register(
            BuiltInRegistries.BLOCK,
            MICRO_CONTAINER_KEY,
            new MicroContainerBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(0.4F)
                    .setId(MICRO_CONTAINER_KEY))
    );

    public static final BlockEntityType<MicroContainerBlockEntity> MICRO_CONTAINER_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            MICRO_CONTAINER_ID,
            FabricBlockEntityTypeBuilder.create(MicroContainerBlockEntity::new, MICRO_CONTAINER).build()
    );

    private ModContent() {}

    public static void initialize() {}
}
