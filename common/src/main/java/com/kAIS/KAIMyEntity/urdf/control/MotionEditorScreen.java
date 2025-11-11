package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFJoint;
import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFJoint;
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
 * - 페이지 분할(Prev/Next)
 * - 각 관절 행: [-] 슬라이더 [+]  (URDF limit 기반)
 * - 상단: Prev / Next / Page, Reset All
 * - 하단: Exit
 *
 * 하위호환:
 *  - new MotionEditorScreen(renderer)  → 슬라이더가 renderer에 직접 씀
 *  - new MotionEditorScreen(renderer, bus) → 슬라이더는 bus.setManual(...)만 호출
 */
public class MotionEditorScreen extends Screen {
    private final URDFModelOpenGLWithSTL renderer;
    private final JointControlBus bus; // null이면 하위호환 모드(직접 renderer 호출)

    private final List<Row> rows = new ArrayList<>();
    private int page = 0;
    private final int perPage = 14; // 페이지당 관절 수

    // ===== 하위호환 생성자 =====
    public MotionEditorScreen(URDFModelOpenGLWithSTL renderer) {
        super(Component.literal("URDF Joint Editor"));
        this.renderer = renderer;
        this.bus = null;
    }

    // ===== 권장: 버스 주입 생성자 =====
    public MotionEditorScreen(URDFModelOpenGLWithSTL renderer, JointControlBus bus) {
        super(Component.literal("URDF Joint Editor"));
        this.renderer = renderer;
        this.bus = bus;
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

        // ===== 페이지 컨트롤 =====
        addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> {
            if (page > 0) { page--; rebuild(); }
        }).bounds(leftX, headerY, 60, 20).build());

        int total  = renderer.getRobotModel().joints.size();
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
            if (bus != null) {
                bus.clearManualAll(); // 수동 오버라이드 해제
            } else {
                for (URDFJoint j : renderer.getRobotModel().joints) {
                    renderer.setJointPreview(j.name, 0f);
                    renderer.setJointTarget(j.name, 0f);
                }
            }
            for (Row r : rows) r.slider.setFromRadians(0f);
        }).bounds(width - 100, headerY, 80, 20).build());

        // ===== 관절 리스트 (현재 페이지) =====
        int start = page * perPage;
        int end   = Math.min(total, start + perPage);

        int y = listTop;
        List<URDFJoint> joints = renderer.getRobotModel().joints;

        for (int i = start; i < end; i++) {
            URDFJoint j = joints.get(i);

            // 리미트 (없으면 -180~180도)
            float lo = (j.limit != null && j.limit.hasLimits()) ? j.limit.lower : (float)Math.toRadians(-180);
            float hi = (j.limit != null && j.limit.hasLimits()) ? j.limit.upper : (float)Math.toRadians( 180);
            if (hi <= lo) { lo = (float)Math.toRadians(-180); hi = (float)Math.toRadians(180); }

            final String jointName = j.name;
            final float loF = lo, hiF = hi;

            // [-] 조그 — 슬라이더 현재값 기준으로 step
            addRenderableWidget(Button.builder(Component.literal("-"), b -> {
                float step = (float)Math.toRadians(2.0);
                float cur  = currentRadiansOf(jointName, j.currentPosition, loF, hiF);
                float v    = clamp(cur - step, loF, hiF);
                applyJoint(jointName, v);
                syncRow(jointName, v);
            }).bounds(leftX, y, 20, 20).build());

            // 슬라이더 (0..1 -> lo..hi)
            JointSlider slider = new JointSlider(
                    leftX + 24, y, 260, 20,
                    jointName, currentRadiansOf(jointName, j.currentPosition, loF, hiF), loF, hiF
            );
            rows.add(new Row(jointName, slider));
            addRenderableWidget(slider);

            // [+] 조그
            addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                float step = (float)Math.toRadians(2.0);
                float cur  = currentRadiansOf(jointName, j.currentPosition, loF, hiF);
                float v    = clamp(cur + step, loF, hiF);
                applyJoint(jointName, v);
                syncRow(jointName, v);
            }).bounds(leftX + 288, y, 20, 20).build());

            y += 24;
        }

        // Exit
        addRenderableWidget(Button.builder(Component.literal("Exit"), b -> {
            Minecraft.getInstance().setScreen(null);
        }).bounds(width - 70, height - 30, 50, 20).build());
    }

    /** 슬라이더가 이미 뜬 경우 그 값을 신뢰(버스/프리뷰 동기화 지연 방지) */
    private float currentRadiansOf(String jointName, float fallbackCurrent, float lo, float hi) {
        for (Row r : rows) {
            if (r.jointName.equals(jointName)) {
                return clamp(r.slider.getRadians(), lo, hi);
            }
        }
        return clamp(fallbackCurrent, lo, hi);
    }

    /** 실제 적용 (버스 있으면 수동 오버라이드, 없으면 직접 렌더러) */
    private void applyJoint(String jointName, float radians) {
        if (bus != null) {
            bus.setManual(jointName, radians);
        } else {
            renderer.setJointPreview(jointName, radians);
            renderer.setJointTarget(jointName, radians);
        }
    }

    private void syncRow(String jointName, float radians) {
        for (Row r : rows) {
            if (r.jointName.equals(jointName)) {
                r.slider.setFromRadians(radians);
                break;
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        renderBackground(g, mouseX, mouseY, partialTicks);
        super.render(g, mouseX, mouseY, partialTicks);
        g.drawCenteredString(font, "URDF Joint Editor (Immediate)", width / 2, 2, 0xFFFFFF);
    }

    // ===== 내부 구조 =====
    private record Row(String jointName, JointSlider slider) {}

    private class JointSlider extends AbstractSliderButton {
        private final String jointName;
        private final float lo, hi;

        /** current(rad)를 lo..hi 기준 0..1로 정규화하여 초기화 */
        public JointSlider(int x, int y, int w, int h,
                           String jointName, float currentRad, float lo, float hi) {
            // ⚠ super(...) 이전에는 this를 참조할 수 없으므로 정적 유틸 사용
            super(x, y, w, h, Component.literal(""), normalize01(currentRad, lo, hi));
            this.jointName = jointName;
            this.lo = lo;
            this.hi = hi;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float rad = denorm((float) value);
            int deg = Math.round((float)Math.toDegrees(rad));
            setMessage(Component.literal(jointName + ": " + deg + "°"));
        }

        @Override
        protected void applyValue() {
            float rad = denorm((float) value);
            applyJoint(jointName, rad); // 버스 or 직접
        }

        @Override
        public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
            boolean r = super.mouseDragged(mx, my, button, dx, dy);
            float rad = denorm((float) value);
            applyJoint(jointName, rad);
            return r;
        }

        /** 외부에서 라디안으로 동기화(조그/리셋) */
        public void setFromRadians(float rad) {
            this.value = normalize01(rad, lo, hi);
            updateMessage();
        }

        /** 현재 슬라이더 라디안 값 조회 */
        public float getRadians() {
            return denorm((float) value);
        }

        private float denorm(float v01) { return lo + v01 * (hi - lo); }
    }

    // ===== 정적 유틸 (super(...) 인자에서 사용 가능) =====
    private static float normalize01(float v, float lo, float hi) {
        if (hi - lo <= 1e-6f) return 0.5f;
        float t = (v - lo) / (hi - lo);
        return t < 0 ? 0 : Math.min(1, t);
    }
    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(hi, v);
    }
}

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

        // ===== 페이지 컨트롤 =====
        addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> {
            if (page > 0) { page--; rebuild(); }
        }).bounds(leftX, headerY, 60, 20).build());

        int total  = renderer.getRobotModel().joints.size();
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
            for (URDFJoint j : renderer.getRobotModel().joints) {
                renderer.setJointPreview(j.name, 0f); // 즉시
                renderer.setJointTarget(j.name, 0f);  // 안정 추종
            }
            for (Row r : rows) r.slider.setFromRadians(0f);
        }).bounds(width - 100, headerY, 80, 20).build());

        // ===== 관절 리스트 (현재 페이지) =====
        int start = page * perPage;
        int end   = Math.min(total, start + perPage);

        int y = listTop;
        List<URDFJoint> joints = renderer.getRobotModel().joints;

        for (int i = start; i < end; i++) {
            URDFJoint j = joints.get(i);

            // 리미트 (없으면 -180~180도)
            float lo = (j.limit != null && j.limit.hasLimits()) ? j.limit.lower : (float)Math.toRadians(-180);
            float hi = (j.limit != null && j.limit.hasLimits()) ? j.limit.upper : (float)Math.toRadians( 180);
            if (hi <= lo) { lo = (float)Math.toRadians(-180); hi = (float)Math.toRadians(180); }

            // 🔧 람다용 final 복사본 (중요!)
            final URDFJoint jRef = j;
            final float loF = lo, hiF = hi;

            // [-] 조그
            addRenderableWidget(Button.builder(Component.literal("-"), b -> {
                float step = (float)Math.toRadians(2.0);
                float v = clamp(jRef.currentPosition - step, loF, hiF);
                renderer.setJointPreview(jRef.name, v);
                renderer.setJointTarget(jRef.name, v);
                syncRow(jRef.name, v);
            }).bounds(leftX, y, 20, 20).build());

            // 슬라이더 (0..1 -> lo..hi)
            JointSlider slider = new JointSlider(leftX + 24, y, 260, 20,
                    jRef.name, jRef.currentPosition, loF, hiF, renderer);
            rows.add(new Row(jRef.name, slider));
            addRenderableWidget(slider);

            // [+] 조그
            addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                float step = (float)Math.toRadians(2.0);
                float v = clamp(jRef.currentPosition + step, loF, hiF);
                renderer.setJointPreview(jRef.name, v);
                renderer.setJointTarget(jRef.name, v);
                syncRow(jRef.name, v);
            }).bounds(leftX + 288, y, 20, 20).build());

            y += 24;
        }

        // Exit
        addRenderableWidget(Button.builder(Component.literal("Exit"), b -> {
            Minecraft.getInstance().setScreen(null);
        }).bounds(width - 70, height - 30, 50, 20).build());
    }

    private void syncRow(String jointName, float radians) {
        for (Row r : rows) {
            if (r.jointName.equals(jointName)) {
                r.slider.setFromRadians(radians);
                break;
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        renderBackground(g, mouseX, mouseY, partialTicks);
        super.render(g, mouseX, mouseY, partialTicks);
        g.drawCenteredString(font, "URDF Joint Editor (Immediate)", width / 2, 2, 0xFFFFFF);
    }

    // ===== 내부 구조 =====
    private record Row(String jointName, JointSlider slider) {}

    private static class JointSlider extends AbstractSliderButton {
        private final String jointName;
        private final URDFModelOpenGLWithSTL renderer;
        private final float lo, hi;

        /** current(rad)를 lo..hi 기준 0..1로 정규화하여 초기화 */
        public JointSlider(int x, int y, int w, int h,
                           String jointName, float currentRad, float lo, float hi,
                           URDFModelOpenGLWithSTL renderer) {
            super(x, y, w, h, Component.literal(""), normalize(currentRad, lo, hi));
            this.jointName = jointName;
            this.renderer = renderer;
            this.lo = lo;
            this.hi = hi;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float rad = denorm((float) value);
            int deg = Math.round((float)Math.toDegrees(rad));
            setMessage(Component.literal(jointName + ": " + deg + "°"));
        }

        @Override
        protected void applyValue() {
            float rad = denorm((float) value);
            renderer.setJointPreview(jointName, rad); // 즉시 화면 반영
            renderer.setJointTarget(jointName, rad);  // 틱에서 안정 추종
        }

        @Override
        public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
            boolean r = super.mouseDragged(mx, my, button, dx, dy);
            float rad = denorm((float) value);
            renderer.setJointPreview(jointName, rad); // 드래그 중 매 프레임
            renderer.setJointTarget(jointName,  rad); // ★ 추가: 드래그 중에도 target 동기화
            return r;
        }

        /** 외부에서 라디안으로 동기화(조그/리셋) */
        public void setFromRadians(float rad) {
            this.value = normalize(rad, lo, hi);
            updateMessage();
        }

        private float denorm(float v01) { return lo + v01 * (hi - lo); }
        private static float normalize(float v, float lo, float hi) {
            if (hi - lo <= 1e-6f) return 0.5f;
            float t = (v - lo) / (hi - lo);
            return t < 0 ? 0 : Math.min(1, t);
        }
    }

    // ===== 유틸 =====
    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(hi, v);
    }
}


