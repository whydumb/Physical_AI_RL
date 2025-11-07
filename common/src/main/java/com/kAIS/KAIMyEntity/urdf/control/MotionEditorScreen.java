package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFJoint;
import com.kAIS.KAIMyEntity.urdf.URDFRobotModel;
import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * URDF Joint Editor (즉시 적용 전용)
 * - 키프레임/재생 제거
 * - 페이지 분할(Prev/Next)
 * - 각 관절 행: [-] 슬라이더 [+]  (리미트 기반 스케일)
 * - 상단: Prev / Next / Page, Reset All
 * - 하단: Exit
 *
 * 요구:
 * - URDFModelOpenGLWithSTL에 getRobotModel(), setJointPreview(name,rad), setJointTarget(name,rad)
 * - ClientTickLoop에서 renderer.tickUpdate(1/20f)
 */
public class MotionEditorScreen extends Screen {
    private final URDFModelOpenGLWithSTL renderer;
    private final List<Row> rows = new ArrayList<>();

    private int page = 0;
    private final int perPage = 14; // 페이지당 관절 수

    public MotionEditorScreen(URDFModelOpenGLWithSTL renderer) {
        super(Component.literal("URDF Joint Editor"));
        this.renderer = renderer;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        rows.clear();

        int headerY = 10;
        int listTop  = 42;
        int leftX    = 20;

        // 안전 캐스팅
        URDFRobotModel model = (URDFRobotModel) renderer.getRobotModel();
        if (model == null || model.joints == null) return;

        // ===== 페이지 컨트롤 =====
        addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> {
            if (page > 0) { page--; rebuild(); }
        }).bounds(leftX, headerY, 60, 20).build());

        int total  = model.joints.size();
        int pages  = Math.max(1, (int)Math.ceil(total / (double)perPage));

        addRenderableWidget(Button.builder(Component.literal("Next >"), b -> {
            if (page < pages - 1) { page++; rebuild(); }
        }).bounds(leftX + 66, headerY, 60, 20).build());

        Button pageLabel = Button.builder(Component.literal("Page " + (page+1) + "/" + pages), b -> {})
                .bounds(leftX + 132, headerY, 90, 20).build();
        pageLabel.active = false;
        addRenderableWidget(pageLabel);

        // ===== Reset All =====
        addRenderableWidget(Button.builder(Component.literal("Reset All"), b -> {
            for (URDFJoint j : model.joints) {
                renderer.setJointPreview(j.name, 0f); // 즉시
                renderer.setJointTarget(j.name, 0f);  // 안정 추종
            }
            for (Row r : rows) r.slider.setFromRadians(0f);
        }).bounds(width - 100, headerY, 80, 20).build());

        // ===== 관절 리스트 (현재 페이지) =====
        int start = page * perPage;
        int end   = Math.min(total, start + perPage);

        int y = listTop;
        List<URDFJoint> joints = model.joints;

        for (int i = start; i < end; i++) {
            URDFJoint j = joints.get(i);

            // 리미트 (없으면 -180~180도)
            float lo = (j.limit != null && j.limit.hasLimits()) ? j.limit.lower : (float)Math.toRadians(-180);
