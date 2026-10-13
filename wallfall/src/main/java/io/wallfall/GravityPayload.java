package io.wallfall;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

public record GravityPayload(int code) implements CustomPayload {
    public static final CustomPayload.Id<GravityPayload> ID =
            new CustomPayload.Id<>(Identifier.of("wallfall", "wall_direction"));

    public static final PacketCodec<RegistryByteBuf, GravityPayload> CODEC = PacketCodec.of(
            (payload, buffer) -> buffer.writeByte(payload.code()),
            buffer -> new GravityPayload(buffer.readUnsignedByte())
    );

    public static GravityPayload of(Direction direction) {
        return new GravityPayload(switch (direction) {
            case NORTH -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            case EAST -> 4;
            default -> 0;
        });
    }

    public Direction direction() {
        return switch (code) {
            case 1 -> Direction.NORTH;
            case 2 -> Direction.SOUTH;
            case 3 -> Direction.WEST;
            case 4 -> Direction.EAST;
            default -> Direction.DOWN;
        };
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
