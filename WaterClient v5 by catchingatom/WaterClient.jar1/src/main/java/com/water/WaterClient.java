package com.water;

import com.water.gui.ClickGuiScreen;
import com.water.gui.NotificationManager;
import com.water.gui.SpotifyOverlay;
import com.water.module.ActivatableModule;
import com.water.module.Module;
import com.water.module.ModuleManager;
import com.water.module.modules.client.HUD;
import com.water.module.modules.client.SpotifyHUD;
import com.water.module.modules.client.Water;
import com.water.module.modules.misc.NameTags;
import com.water.module.modules.render.PearlESP;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.KeyBinding.Category;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil.Type;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.water.module.modules.donut.SUSChunkFinder;
import com.water.module.modules.donut.ChestSusFinder;
import com.water.module.modules.donut.PlayerDebug;
import com.water.module.modules.donut.SpawnerDebug;
public class WaterClient implements ClientModInitializer {
   public static final Logger LOGGER = LoggerFactory.getLogger("Water");
   private static KeyBinding rightShiftKey;

   public WaterClient() {
   }

   public static boolean isMenuKey(int var0, int var1) {
      return var0 == Water.getGuiKeyCode();
   }

   public static void toggleClickGui() {
      MinecraftClient minecraftClient = MinecraftClient.getInstance();
      if (minecraftClient != null) {
         minecraftClient.execute(() -> {
            if (minecraftClient.currentScreen instanceof ClickGuiScreen) {
               minecraftClient.setScreen(null);
            } else {
               ClickGuiScreen.open();
            }
         });
      }
   }

   @Override
   public void onInitializeClient() {
      LOGGER.info("Cracked by github.com/TrilliumSolutions");
      ModuleManager.INSTANCE.init();
      rightShiftKey = KeyBindingHelper.registerKeyBinding(
         new KeyBinding("key.water.toggle_menu", Type.KEYSYM, 344, new Category(Identifier.of("water", "general")))
      );
      HudRenderCallback.EVENT.register((var0, var1) -> {
         NameTags.renderHud(var0, var1.getTickProgress(false));
         SUSChunkFinder.renderHud(var0, var1.getTickProgress(false));
         ChestSusFinder.renderHud(var0, var1.getTickProgress(false));
         PlayerDebug.renderHud(var0, var1.getTickProgress(false));
         SpawnerDebug.renderHud(var0, var1.getTickProgress(false));
         PearlESP.renderHud(var0, var1.getTickProgress(false));
         HUD.renderHud(var0);
         SpotifyHUD.renderHud(var0);
         SpotifyOverlay.visible = HUD.isSpotifyQueueEnabled();
         if (SpotifyOverlay.visible) {
            MinecraftClient minecraftClient = MinecraftClient.getInstance();
            if (minecraftClient != null) {
               double d0 = minecraftClient.getWindow().getScaleFactor();
               double d1 = minecraftClient.mouse.getX() / d0;
               double d2 = minecraftClient.mouse.getY() / d0;
               SpotifyOverlay.render(var0, d1, d2);
            }
         }

         NotificationManager.INSTANCE.render(var0);
      });
      ClientTickEvents.END_CLIENT_TICK.register(var0 -> {
         ModuleManager.INSTANCE.onTick();
         if (var0.currentScreen == null && var0.getWindow() != null) {
            for (Module module : ModuleManager.INSTANCE.getModules()) {
               int i = module.getBind();
               ActivatableModule activatableModule = module instanceof ActivatableModule activatableModule1 ? activatableModule1 : null;
               int j = activatableModule != null ? activatableModule.getActivationKey() : 0;
               if (i != 0) {
                  try {
                     boolean flag = GLFW.glfwGetKey(var0.getWindow().getHandle(), i) == 1;
                     if (flag && !module.wasBindPressed && j != i) {
                        module.onBindPressed();
                     }

                     module.wasBindPressed = flag;
                  } catch (Exception exception1) {
                  }
               }

               if (activatableModule != null && j != 0) {
                  try {
                     boolean flag1 = GLFW.glfwGetKey(var0.getWindow().getHandle(), j) == 1;
                     if (flag1 && !activatableModule.wasActivationKeyPressed) {
                        activatableModule.onActivationKeyPressed();
                     }

                     activatableModule.wasActivationKeyPressed = flag1;
                  } catch (Exception exception) {
                  }
               }
            }
         }
      });
   }
}
