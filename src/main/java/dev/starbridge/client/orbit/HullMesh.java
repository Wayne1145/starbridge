package dev.starbridge.client.orbit;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;

import dev.starbridge.orbit.OrbitWire;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

import org.joml.Matrix4f;

/**
 * Bakes real block models and their atlas UVs once per hull. Each visible craft then costs one GPU
 * draw call.
 */
final class HullMesh implements AutoCloseable {
    private final OrbitWire.Hull hull;
    private VertexBuffer vertices;

    HullMesh(OrbitWire.Hull hull) {
        this.hull = hull;
        var game = Minecraft.getInstance();
        var builder = new BufferBuilder(64 * 1024);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
        var random = RandomSource.create(42);
        for (var voxel : hull.voxels()) {
            var state = Block.stateById(voxel.block());
            var model = game.getBlockRenderer().getBlockModel(state);
            int count = 0;
            // Fully animated block entities have no baked quads. Their block particle sprite still
            // identifies their hull cell.
            for (int side = 0; side <= Direction.values().length; side++) {
                var face = side == Direction.values().length ? null : Direction.values()[side];
                random.setSeed(42);
                for (var quad : model.getQuads(state, face, random, ModelData.EMPTY, null)) {
                    addQuad(builder, state, voxel, quad);
                    if (++count == 48) break;
                }
                if (count >= 48) break;
            }
            if (count == 0) cube(builder, voxel, model.getParticleIcon(ModelData.EMPTY));
        }
        var compiled = builder.endOrDiscardIfEmpty();
        if (compiled != null) {
            vertices = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vertices.bind();
            try {
                vertices.upload(compiled);
            } finally {
                VertexBuffer.unbind();
            }
        }
    }

    private static float shade(Direction face) {
        return switch (face) {
            case UP -> 1.0f;
            case SOUTH -> .90f;
            case WEST -> .82f;
            case EAST -> .68f;
            case NORTH -> .72f;
            case DOWN -> .48f;
        };
    }

    private static void addQuad(
            BufferBuilder out, BlockState state, OrbitWire.Voxel voxel, BakedQuad quad) {
        var game = Minecraft.getInstance();
        int tint =
                quad.isTinted() && game.level != null && game.player != null
                        ? game.getBlockColors()
                                .getColor(
                                        state,
                                        game.level,
                                        game.player.blockPosition(),
                                        quad.getTintIndex())
                        : 0xffffff;
        float light = shade(quad.getDirection());
        int[] data = quad.getVertices();
        int stride = data.length / 4;
        if (stride < 6) return;
        for (int n = 0; n < 4; n++) {
            int i = n * stride, vertexColor = data[i + 3];
            int r = (int) ((tint >> 16 & 255) * (vertexColor & 255) / 255f * light);
            int g = (int) ((tint >> 8 & 255) * (vertexColor >> 8 & 255) / 255f * light);
            int b = (int) ((tint & 255) * (vertexColor >> 16 & 255) / 255f * light);
            out.vertex(
                            voxel.x() + Float.intBitsToFloat(data[i]) * voxel.width(),
                            voxel.y() + Float.intBitsToFloat(data[i + 1]) * voxel.height(),
                            voxel.z() + Float.intBitsToFloat(data[i + 2]) * voxel.depth())
                    .color(r, g, b, vertexColor >>> 24)
                    .uv(Float.intBitsToFloat(data[i + 4]), Float.intBitsToFloat(data[i + 5]))
                    .endVertex();
        }
    }

    private static final int[][] FACES = {
        {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1}, {4, 5, 7, 6}, {0, 4, 6, 2}, {1, 3, 7, 5}
    };

    private static void cube(BufferBuilder out, OrbitWire.Voxel cell, TextureAtlasSprite sprite) {
        for (var face : Direction.values()) {
            int light = (int) (255 * shade(face));
            int[] corners = FACES[face.get3DDataValue()];
            for (int n = 0; n < 4; n++) {
                int c = corners[n];
                out.vertex(
                                cell.x() + (c & 1) * cell.width(),
                                cell.y() + (c >> 1 & 1) * cell.height(),
                                cell.z() + (c >> 2 & 1) * cell.depth())
                        .color(light, light, light, 255)
                        .uv(
                                n <= 1 ? sprite.getU0() : sprite.getU1(),
                                n == 0 || n == 3 ? sprite.getV1() : sprite.getV0())
                        .endVertex();
            }
        }
    }

    void draw(GuiGraphics g, float x, float y, float size, float yaw, float elevation, float bank) {
        if (vertices == null) return;
        var pose = g.pose();
        float scale = size / Math.max(hull.width(), Math.max(hull.height(), hull.depth()));
        g.flush();
        pose.pushPose();
        try {
            pose.translate(x, y, 120);
            pose.scale(scale, -scale, -scale);
            pose.mulPose(Axis.ZP.rotationDegrees(bank));
            pose.mulPose(Axis.XP.rotationDegrees(elevation));
            pose.mulPose(Axis.YP.rotationDegrees(yaw));
            pose.translate(-hull.width() * .5, -hull.height() * .5, -hull.depth() * .5);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            var view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
            vertices.bind();
            vertices.drawWithShader(
                    view,
                    RenderSystem.getProjectionMatrix(),
                    GameRenderer.getPositionColorTexShader());
        } finally {
            VertexBuffer.unbind();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            pose.popPose();
        }
    }

    @Override
    public void close() {
        if (vertices != null) {
            vertices.close();
            vertices = null;
        }
    }
}
