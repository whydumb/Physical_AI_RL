package com.kAIS.KAIMyEntity;

import com.kAIS.KAIMyEntity.renderer.MMDModelManager;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * 깔끔하게 정리된 클라이언트 초기화
 * - URDF 모델 매니저만 초기화
 * - MMD, VMC, 모션 에디터 모두 제거
 */
public class KAIMyEntityClient {
    public static final Logger logger = LogManager.getLogger();
    static final Minecraft MCinstance = Minecraft.getInstance();
    static final String gameDirectory = MCinstance.gameDirectory.getAbsolutePath();

    public static void initClient() {
        checkKAIMyEntityFolder();
        MMDModelManager.Init();  // URDF 모델 매니저만 초기화
        logger.info("KAIMyEntityClient initialized (URDF + Webots)");
    }

    private static void checkKAIMyEntityFolder(){
        File KAIMyEntityFolder = new File(gameDirectory + "/KAIMyEntity");
        if (!KAIMyEntityFolder.exists()){
            logger.info("KAIMyEntity folder not found, creating...");
            KAIMyEntityFolder.mkdir();
        }
    }
}