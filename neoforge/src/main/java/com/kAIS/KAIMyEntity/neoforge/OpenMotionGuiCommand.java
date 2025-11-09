package com.kAIS.KAIMyEntity.client.command;

import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import com.kAIS.KAIMyEntity.urdf.control.MotionEditorScreen;
import com.kAIS.KAIMyEntity.client.gui.VMCMappingEditorScreen;
import com.kAIS.KAIMyEntity.client.gui.EditorSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;

public class OpenMotionGuiCommand {
    
    public static int execute(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        
        // ClientTickLoop에서 renderer 가져오기
        URDFModelOpenGLWithSTL renderer = getRendererFromClientTickLoop();
        
        if (renderer == null) {
            ctx.getSource().sendFailure(Component.literal("URDF 렌더러를 찾을 수 없습니다."));
            return 0;
        }
        
        // ✅ 선택 화면 열기: "Joint Editor" vs "VMC Mapping"
        mc.execute(() -> {
            mc.setScreen(new EditorSelectionScreen(null, renderer));
        });
        
        return Command.SINGLE_SUCCESS;
    }
    
    private static URDFModelOpenGLWithSTL getRendererFromClientTickLoop() {
        try {
            Class<?> cls = Class.forName("com.kAIS.KAIMyEntity.neoforge.ClientTickLoop");
            java.lang.reflect.Field f = cls.getField("renderer");
            Object o = f.get(null);
            if (o instanceof URDFModelOpenGLWithSTL) {
                return (URDFModelOpenGLWithSTL) o;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
