package com.water.module.modules.donut;

import com.water.module.Category;
import com.water.module.Module;
import com.water.module.setting.Setting;
import com.water.render.FontRenderer;
import com.water.render.RenderUtils;
import com.water.render.ShapeBatch;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.opengl.GL11;

public final class SUSChunkFinder extends Module {
   public static SUSChunkFinder INSTANCE;

   public final Setting<Integer> scanRadius = new Setting<>("Scan Radius", 1, 1, 5);
   public final Setting<Integer> clusterThreshold = new Setting<>("Sim Chunks", 10, 1, 10);
   public final Setting<Integer> minCount = new Setting<>("Min Amethyst", 5, 1, 40);
   public final Setting<Integer> displayY = new Setting<>("Display Y", 47, -64, 320);
   public final Setting<Color> fillColor = new Setting<>("Fill Color", new Color(160, 60, 220, 55));
   public final Setting<Integer> fillAlpha = new Setting<>("Fill Alpha", 55, 0, 255);
   public final Setting<Boolean> showInfo = new Setting<>("Show % List", true);
   public final Setting<Boolean> showOutline = new Setting<>("Outline", true);

   // Never cleared mid-scan — only replaced when new results are ready
   public volatile Map<ChunkPos, Integer> stableCounts = Collections.emptyMap();
   public volatile List<ChunkPos> stableHits = Collections.emptyList();

   private ExecutorService scanExec;
   private final AtomicBoolean scanning = new AtomicBoolean(false);
   private int tickCount = 0;

   public SUSChunkFinder() {
      super("Amethyst Sus Finder", Category.DONUT);
      INSTANCE = this;
      this.addSetting(this.scanRadius);
      this.addSetting(this.clusterThreshold);
      this.addSetting(this.minCount);
      this.addSetting(this.displayY);
      this.addSetting(this.fillColor);
      this.addSetting(this.fillAlpha);
      this.addSetting(this.showInfo);
      this.addSetting(this.showOutline);
   }

   @Override
   public void onEnable() {
      this.stableCounts = Collections.emptyMap();
      this.stableHits = Collections.emptyList();
      this.tickCount = 0;
      this.scanning.set(false);
   }

   @Override
   public void onDisable() {
      this.stableCounts = Collections.emptyMap();
      this.stableHits = Collections.emptyList();
      this.scanning.set(false);
      if (this.scanExec != null) {
         this.scanExec.shutdownNow();
         this.scanExec = null;
      }
   }

   @Override
   public void onTick() {
      if (mc.world == null || mc.player == null) return;
      // 20 ticks = 1 second between scans — reduces flicker a lot
      if (++this.tickCount % 20 != 0) return;
      if (!this.scanning.compareAndSet(false, true)) return;

      if (this.scanExec == null || this.scanExec.isShutdown()) {
         this.scanExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "amethyst-sus");
            t.setDaemon(true);
            return t;
         });
      }

      final ChunkPos origin = mc.player.getChunkPos();
      final int radius = this.scanRadius.getValue() * 5;
      final int min = this.minCount.getValue();
      final int cluster = this.clusterThreshold.getValue();

      final List<ChunkPos> positions = new ArrayList<>();
      final List<WorldChunk> chunks = new ArrayList<>();
      for (int dx = -radius; dx <= radius; dx++) {
         for (int dz = -radius; dz <= radius; dz++) {
            ChunkPos cp = new ChunkPos(origin.x + dx, origin.z + dz);
            WorldChunk wc = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z, false);
            if (wc != null && !wc.isEmpty()) {
               positions.add(cp);
               chunks.add(wc);
            }
         }
      }

      this.scanExec.submit(() -> {
         try {
            HashMap<ChunkPos, Integer> countMap = new HashMap<>();
            HashMap<ChunkPos, Integer> found = new HashMap<>();
            for (int i = 0; i < positions.size(); i++) {
               int c = countAmethyst(chunks.get(i));
               countMap.put(positions.get(i), c);
               if (c >= min) found.put(positions.get(i), c);
            }
            List<ChunkPos> top = pickTop(found, origin, cluster);
            // atomic publish — old list stays visible until this line
            this.stableCounts = Collections.unmodifiableMap(countMap);
            this.stableHits = Collections.unmodifiableList(top);
         } catch (Throwable ignored) {
         } finally {
            this.scanning.set(false);
         }
      });
   }

   private static List<ChunkPos> pickTop(Map<ChunkPos, Integer> map, ChunkPos origin, int limit) {
      ArrayList<Map.Entry<ChunkPos, Integer>> list = new ArrayList<>(map.entrySet());
      list.sort((a, b) -> {
         int c = Integer.compare(b.getValue(), a.getValue());
         if (c != 0) return c;
         int da = dist2(a.getKey(), origin), db = dist2(b.getKey(), origin);
         return Integer.compare(da, db);
      });
      LinkedHashSet<ChunkPos> set = new LinkedHashSet<>();
      for (Map.Entry<ChunkPos, Integer> e : list) {
         if (set.size() >= Math.max(1, limit)) break;
         set.add(e.getKey());
      }
      return new ArrayList<>(set);
   }

   private static int dist2(ChunkPos a, ChunkPos o) {
      int dx = a.x - o.x, dz = a.z - o.z;
      return dx * dx + dz * dz;
   }

   private int countAmethyst(WorldChunk chunk) {
      int count = 0;
      ChunkSection[] sections = chunk.getSectionArray();
      int bottom = chunk.getBottomY();
      for (int s = 0; s < sections.length; s++) {
         if (bottom + s * 16 > 32) break;
         ChunkSection section = sections[s];
         if (section == null || section.isEmpty() || !section.hasAny(this::isAmethyst)) continue;
         for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
               for (int z = 0; z < 16; z++)
                  if (isAmethyst(section.getBlockState(x, y, z))) count++;
      }
      return count;
   }

   private boolean isAmethyst(BlockState state) {
      return state.isOf(Blocks.AMETHYST_CLUSTER) || state.isOf(Blocks.AMETHYST_BLOCK)
         || state.isOf(Blocks.BUDDING_AMETHYST)
         || state.isOf(Blocks.SMALL_AMETHYST_BUD)
         || state.isOf(Blocks.MEDIUM_AMETHYST_BUD)
         || state.isOf(Blocks.LARGE_AMETHYST_BUD);
   }

   private int calcPercent(int count) {
      return Math.min(100, Math.max(0, Math.round(count * 1.25f)));
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      List<ChunkPos> hits = this.stableHits;
      if (hits.isEmpty() || mc.world == null || mc.player == null) return;

      Camera camera = RenderUtils.getCamera();
      if (camera == null) return;
      Vec3d cam = RenderUtils.getCameraPos(camera);

      double y = this.displayY.getValue() - cam.y;
      double y2 = y + 0.2;

      Color base = this.fillColor.getValue();
      int alpha = this.fillAlpha.getValue();
      Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
      Color outline = new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.min(255, alpha + 100));

      matrices.push();
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glEnable(GL11.GL_BLEND);
      ShapeBatch batch = RenderUtils.beginShapeBatch(matrices);
      for (ChunkPos cp : hits) {
         double x1 = (cp.x << 4) - cam.x;
         double z1 = (cp.z << 4) - cam.z;
         batch.renderFilledBox(x1, y, z1, x1 + 16.0, y2, z1 + 16.0, fill);
         if (this.showOutline.getValue()) {
            batch.renderOutlineBox(x1, y, z1, x1 + 16.0, y2, z1 + 16.0, outline);
         }
      }
      batch.flush();
      GL11.glEnable(GL11.GL_DEPTH_TEST);
      matrices.pop();
   }

   public static void renderHud(DrawContext context, float tickDelta) {
      SUSChunkFinder self = INSTANCE;
      if (self == null || !self.isEnabled() || !self.showInfo.getValue()) return;
      List<ChunkPos> hits = self.stableHits;
      if (hits.isEmpty()) return;

      Map<ChunkPos, Integer> counts = self.stableCounts;
      int y = 36;
      FontRenderer.INSTANCE.drawString(context, "Amethyst Sus", 4, y, 0xFFCC88FF);
      y += 12;
      int n = 0;
      for (ChunkPos cp : hits) {
         if (n >= 12) break;
         int count = counts.getOrDefault(cp, 0);
         int pct = self.calcPercent(count);
         int color = pct >= 80 ? 0xFFFF5555 : (pct >= 50 ? 0xFFFFAA00 : 0xFFCC88FF);
         FontRenderer.INSTANCE.drawString(context,
            String.format("  [%d %d] %d%% Am%d", cp.x, cp.z, pct, count), 4, y, color);
         y += 11;
         n++;
      }
   }
}
