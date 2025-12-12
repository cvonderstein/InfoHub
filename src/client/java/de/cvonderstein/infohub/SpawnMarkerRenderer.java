package de.cvonderstein.infohub;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Renders spawnable-block markers in-world (toggleable).
 *
 * We render a simple outline box around the "spawn air block" (where a mob's feet would be),
 * because it is robust across mappings / render pipeline changes.
 */
public final class SpawnMarkerRenderer {
    private SpawnMarkerRenderer() {}

    public static void onWorldRender(WorldRenderContext context) {
        InfoHubState s = InfoHubState.INSTANCE;
        if (!s.isSpawnMarkersEnabled()) return;

        List<BlockPos> markers = s.getSpawnMarkerPositions();
        if (markers.isEmpty()) return;

        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        Vec3d camPos = context.camera().getPos();

        matrices.push();
        // Convert world coordinates into camera-relative coordinates.
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());

        for (BlockPos p : markers) {
            // Slightly inflate to avoid z-fighting with block edges.
            Box b = new Box(
                    p.getX(), p.getY(), p.getZ(),
                    p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0
            ).expand(0.002);

            VertexRendering.drawBox(matrices, lines, b, 1.0f, 0.0f, 0.0f, 0.85f);
        }

        matrices.pop();
    }
}
