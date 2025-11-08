package com.kAIS.KAIMyEntity.urdf.control;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kAIS.KAIMyEntity.urdf.URDFJoint;
import com.kAIS.KAIMyEntity.urdf.URDFRobotModel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.*;
import java.util.function.Supplier;

/**
 * URDFMotionEditor (이름 유지) — VMC 본 → URDF 조인트 매핑 + 인터랙티브 캘리브레이션 GUI
 *
 * - 좌: 실시간 VMC 본 목록(HumanoidTag 이름) 선택
 * - 우: URDF 조인트 목록(현재각/리밋) 선택 + 슬라이더로 수동구동
 * - 중앙: 선택 상태 표시 + "Record / Stop+Fit / Add/Update Mapping" + 매핑 리스트 + Save/Load + Live Preview
 * - 실시간으로 본 회전(Quaternion) → 조인트축 투영 각도(2*atan2(v·a, w))을 샘플링하여 회귀로 multiplier/offset 산출
 * - Live Preview: VmcMarionetteManager.getState()를 주기적으로 읽어 setJointPreview() 적용
 *
 * ⚙️ 의존성:
 *  - 이 스크린은 renderer(Object)에 대해 다음 시그니처를 리플렉션 수행합니다.
 *      URDFRobotModel getRobotModel()
 *      void setJointPreview(String,float)
 *      String getModelDir() 또는 GetModelDir()
 *
 *  - VMC 상태는 Class.forName("top.fifthlight.armorstand.vmc.VmcMarionetteManager").getMethod("getState") 로 취득합니다.
 *
 * 📝 저장 포맷: { "mappings":[ { vmcBone, urdfJoint, multiplier, offset, component, mode, axis:{x,y,z} } ... ] }
 */
public class URDFMotionEditor extends Screen {

    // ------------ VMC 본 목록용 라벨 색/스타일 ------------
    private static final int COLOR_BG      = 0xF016161A; // 전체 배경
    private static final int COLOR_PANEL   = 0xE0202226; // 패널
    private static final int COLOR_TITLE   = 0xFFE4B74A;
    private static final int COLOR_TEXT    = 0xFFECECEC;
    private static final int COLOR_SUB     = 0xFF9CC4F0;
    private static final int COLOR_OK      = 0xFF3ECF8E;
    private static final int COLOR_WARN    = 0xFFE97C20;

    // ------------ VMC 표준 본 목록 (fallback용) ------------
    private static final String[] FALLBACK_VRM_BONES = {
            "Hips","Spine","Chest","UpperChest","Neck","Head",
            "LeftShoulder","LeftUpperArm","LeftLowerArm","LeftHand",
            "RightShoulder","RightUpperArm","RightLowerArm","RightHand",
            "LeftUpperLeg","LeftLowerLeg","LeftFoot","LeftToes",
            "RightUpperLeg","RightLowerLeg","RightFoot","RightToes"
    };

    // ------------ 매핑 데이터 구조 ------------
    public static class VMCMapping {
        public String vmcBone;
        public String urdfJoint;
        public float multiplier = 1.0f; // scale for theta_bone
        public float offset = 0.0f;     // additive bias (rad)
        public String component = "AXIS"; // "AXIS" | "Y" | "X" | "Z" | "pitch" | "yaw" | "roll"
        public ExtractionMode mode = ExtractionMode.AXIS_PROJECTION;
        public float ax = 0, ay = 1, az = 0; // used if mode==AXIS_PROJECTION

        public enum ExtractionMode { EULER_X, EULER_Y, EULER_Z, QUATERNION_ANGLE, AXIS_PROJECTION }

        String label() {
            String extra = switch (mode) {
                case AXIS_PROJECTION -> String.format(Locale.ROOT, "axis(%.2f,%.2f,%.2f)", ax, ay, az);
                case EULER_X -> "EULER_X";
                case EULER_Y -> "EULER_Y";
                case EULER_Z -> "EULER_Z";
                case QUATERNION_ANGLE -> "ANGLE";
            };
            return String.format(Locale.ROOT, "%s → %s (×%.3f, +%.3f, %s)", vmcBone, urdfJoint, multiplier, offset, extra);
        }
    }
    private static class VMCMappingSet { public List<VMCMapping> mappings = new ArrayList<>(); }

    // ------------ 상태/의존성 ------------
    private final Screen parent;
    private final Object renderer; // expect getRobotModel(), setJointPreview(), get/GetModelDir()
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private URDFRobotModel model; // filled in init from renderer.getRobotModel()
    private Object vmcState;      // from VmcMarionetteManager.getState()

    // UI lists
    private BoneList  boneList;
    private JointList jointList;
    private MappingList mappingList;

    // selections
    private String     selectedBone;
    private String     selectedJoint;
    private URDFJoint  selectedJointObj;

    // capture session
    private boolean capturing = false;
    private final List<Float> capBone = new ArrayList<>();
    private final List<Float> capJoint = new ArrayList<>();
    private long   captureStartMs = 0L;

    // regression result (preview)
    private float fitM = 1.0f;
    private float fitC = 0.0f;
    private VMCMapping.ExtractionMode curMode = VMCMapping.ExtractionMode.AXIS_PROJECTION;
    private String curComponent = "AXIS";
    private float ax = 0, ay = 1, az = 0; // axis for projection

    // UI elements
    private Button btnStartCap, btnStopFit, btnAddOrUpdate, btnRemove, btnSave, btnLoad, btnLive, btnClearCap;
    private JointScrubSlider jointSlider;

    private boolean livePreview = false;
    private String  status = "";

    public URDFMotionEditor(Screen parent, Object renderer) {
        super(Component.literal("VMC ↔ URDF Mapping"));
        this.parent = parent;
        this.renderer = renderer;
    }

    // ====================== lifecycle ======================

    @Override
    protected void init() {
        super.init();
        // fetch model via reflection once
        this.model = reflectGetRobotModel();

        final int pad = 8;
        final int titleH = 16;
        int top = pad + titleH + 6;
        int listH = Math.max(120, this.height - top - 180);
        int colW  = (this.width - 3*pad) / 2;
        int leftX = pad;
        int rightX = leftX + colW + pad;

        // --- left: VMC bones list ---
        boneList = new BoneList(minecraft, colW, listH, top, leftX);
        addWidget(boneList);

        // --- right: URDF joints list ---
        jointList = new JointList(minecraft, colW, listH, top, rightX);
        addWidget(jointList);

        // fill lists
        rebuildBoneList();
        rebuildJointList();

        // --- center controls (under lists) ---
        int y = top + listH + 8;

        // Capture buttons row
        btnStartCap = addRenderableWidget(Button.builder(Component.literal("● Record"), b -> {
            if (selectedBone == null || selectedJoint == null) { status = "본/조인트를 먼저 선택하세요."; return; }
            capBone.clear(); capJoint.clear();
            capturing = true;
            captureStartMs = System.currentTimeMillis();
            status = "캡처 시작… 선택한 조인트를 손으로 움직이세요";
            // try to pick axis from joint
            float[] axis = reflectGetJointAxis(selectedJointObj);
            if (axis != null) { ax = axis[0]; ay = axis[1]; az = axis[2]; normAxis(); }
        }).size(90, 20).pos(leftX, y).build());

        btnStopFit = addRenderableWidget(Button.builder(Component.literal("■ Stop + Fit"), b -> {
            if (!capturing) { status = "먼저 [Record] 하세요."; return; }
            capturing = false;
            if (capBone.size() < 5) { status = "샘플이 너무 적습니다."; return; }
            // linear regression y ≈ m*x + c (joint angle vs projected bone angle)
            float[] mc = fitLine(capBone, capJoint);
            fitM = mc[0];  // multiplier
            fitC = mc[1];  // offset
            status = String.format(Locale.ROOT, "fit: θ_joint ≈ %.3f*θ_bone + %.3f", fitM, fitC);
        }).size(120, 20).pos(leftX + 100, y).build());

        btnClearCap = addRenderableWidget(Button.builder(Component.literal("Clear cap"), b -> {
            capBone.clear(); capJoint.clear();
            capturing = false;
            status = "캡처 버퍼 초기화";
        }).size(90, 20).pos(leftX + 230, y).build());

        // joint manual driver
        int sliderY = y + 30;
        jointSlider = new JointScrubSlider(rightX, sliderY, colW, 20, 0f, 0f, val -> {
            if (selectedJointObj != null) {
                reflectSetJointPreview(selectedJointObj.name, val);
            }
        });
        addRenderableWidget(jointSlider);

        // mapping add/remove row
        int mapCtlY = sliderY + 40;
        btnAddOrUpdate = addRenderableWidget(
                Button.builder(Component.literal("Add / Update Mapping"), b -> onAddOrUpdateMapping()).size(220, 20)
                        .pos(leftX, mapCtlY).build()
        );
        btnRemove = addRenderableWidget(
                Button.builder(Component.literal("삭제"), b -> onRemoveSelected()).size(70, 20)
                        .pos(leftX + 230, mapCtlY).build()
        );

        // live preview toggle + save/load
        int bottomY = this.height - 40;
        btnLive = addRenderableWidget(Button.builder(Component.literal("▶ Live Preview: OFF"), b -> {
            livePreview = !livePreview;
            btnLive.setMessage(Component.literal(livePreview ? "■ Stop Preview" : "▶ Live Preview: OFF"));
        }).size(160, 20).pos(pad, bottomY).build());

        btnSave = addRenderableWidget(Button.builder(Component.literal("저장"), b -> onSave()).size(80, 20)
                .pos(this.width - pad - 170, bottomY).build());
        btnLoad = addRenderableWidget(Button.builder(Component.literal("불러오기"), b -> onLoad()).size(80, 20)
                .pos(this.width - pad - 82, bottomY).build());

        // mapping list (bottom full width)
        mappingList = new MappingList(minecraft, this.width - 2*pad, 90, bottomY - 100, pad);
        addWidget(mappingList);

        // start polling VMC for live capture/preview
        // (Screen has tick() in 1.20)
    }

    @Override
    public void tick() {
        super.tick();
        // refresh VMC state for capture/preview
        vmcState = reflectGetVmcState();

        if (capturing && vmcState != null && selectedBone != null && selectedJointObj != null) {
            Map<String, Object> bones = reflectCollectBoneMap(vmcState);
            Object tr = bones.get(selectedBone);
            if (tr != null) {
                float boneVal = extractValue(curMode, curComponent, tr);
                Float jdeg = reflectGetJointAngle(selectedJointObj);
                if (jdeg != null) {
                    capBone.add(boneVal);
                    capJoint.add(jdeg);
                }
            }
        }

        if (livePreview && vmcState != null && !mappingList.children().isEmpty()) {
            applyCurrentMappings(vmcState);
        }

        // update joint slider limits if selection changed
        if (selectedJointObj != null) {
            float lo = selectedJointObj.limit != null ? (float) selectedJointObj.limit.lower : -3.14159f;
            float hi = selectedJointObj.limit != null ? (float) selectedJointObj.limit.upper : 3.14159f;
            jointSlider.setRange(lo, hi);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(g, mouseX, mouseY, partialTicks);

        final int pad = 8;
        final int titleY = 6;
        final int leftX = pad;
        final int colW  = (this.width - 3*pad) / 2;
        final int rightX = leftX + colW + pad;
        final int listTop = 24 + pad;
        final int listH  = Math.max(120, this.height - listTop - 180);

        // panels
        g.fill(leftX,  listTop, leftX  + colW,  listH + listTop,  COLOR_PANEL);
        g.fill(rightX, listTop, rightX + colW,  listH + listTop,  COLOR_PANEL);

        // titles
        g.drawString(this.font, "VMC Bones (choose one, move joints, map)", leftX,  titleY, COLOR_TITLE, false);
        g.drawString(this.font, "URDF Joints (select & drive)",             rightX, titleY, COLOR_TITLE, false);

        super.render(g, mouseX, mouseY, partialTicks);

        // selection readout
        int y = listTop + listH + 8 + 24;
        String s1 = "Bone: "  + (selectedBone     != null ? selectedBone     : "(none)");
        String s2 = "Joint: " + (selectedJoint    != null ? selectedJoint    : "(none)");
        String s3 = "Mode: "  + (curMode == VMCMapping.ExtractionMode.AXIS_PROJECTION
                        ? String.format(Locale.ROOT, "AXIS[%.2f, %.2f, %.2f]  m=%.3f  c=%.3f", ax,ay,az,fitM,fitC)
                        : String.format(Locale.ROOT, "%s  m=%.3f  c=%.3f", curMode.name(), fitM, fitC));
        g.drawString(this.font, s1 + "    |    " + s2, leftX, y, COLOR_TEXT, false);
        g.drawString(this.font, s3, leftX, y + 16, COLOR_SUB, false);

        // capture preview sparkline
        int chartY = y + 40;
        int w = this.width - 2*pad;
        int h = 60;
        g.fill(pad,   chartY,         pad + w,     chartY + h, 0x22111111);
        if (capBone.size() > 1) {
            drawSeries(g, pad, chartY, w, h, capBone, 0xFF3ECF8E);
            drawSeries(g, pad, chartY, w, h, capJoint, 0xFFE97C20);
        }

        // status line
        g.drawString(this.font, status, pad, this.height - 20, COLOR_TEXT, false);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    // =============== Mapping add/update & Save/Load ==============

    private void onAddOrUpdateMapping() {
        if (selectedBone == null || selectedJoint == null) { status = "본/조인트를 선택하세요."; return; }
        VMCMapping m = findMapping(selectedBone, selectedJoint);
        if (m == null) {
            m = new VMCMapping();
            m.vmcBone   = selectedBone;
            m.urdfJoint = selectedJoint;
            mappingList.children().add(new MappingList.Entry(m, mappingList));
        }
        m.multiplier = this.fitM;
        m.offset     = this.fitC;
        m.mode       = this.curMode;
        m.component  = this.curMode == VMCMapping.ExtractionMode.AXIS_PROJECTION ? "AXIS" : toComponent(curMode);
        m.ax = ax; m.ay = ay; m.az = az;
        mappingList.setDirty();
        status = "매핑 저장 완료: " + m.label();
    }
    private void onRemoveSelected() {
        MappingList.Entry e = mappingList.getSelected();
        if (e == null) { status = "삭제할 매핑을 선택하세요."; return; }
        mappingList.children().remove(e);
        status = "삭제 완료: " + e.mapping.vmcBone + " → " + e.mapping.urdfJoint;
    }
    private String toComponent(VMCMapping.ExtractionMode m) {
        return switch (m) {
            case EULER_X -> "X";
            case EULER_Y -> "Y";
            case EULER_Z -> "Z";
            case QUATERNION_ANGLE -> "ANGLE";
            case AXIS_PROJECTION -> "AXIS";
        };
    }

    private void onSave() {
        Path file = getMappingFile();
        VMCMappingSet set = new VMCMappingSet();
        for (var e : mappingList.children()) set.mappings.add(e.mapping);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(set));
            status = "저장 완료: " + file;
        } catch (IOException e) {
            status = "저장 실패: " + e.getMessage();
        }
    }

    private void onLoad() {
        Path file = getMappingFile();
        if (!Files.exists(file)) { status = "파일 없음: " + file; return; }
        try {
            String json = Files.readString(file);
            VMCMappingSet set = gson.fromJson(json, VMCMappingSet.class);
            mappingList.children().clear();
            if (set != null && set.mappings != null) {
                for (VMCMapping m : set.mappings) {
                    mappingList.children().add(new MappingList.Entry(m, mappingList));
                }
            }
            mappingList.setDirty();
            status = "불러오기 완료: " + file;
        } catch (IOException e) {
            status = "불러오기 실패: " + e.getMessage();
        }
    }

    private Path getMappingFile() {
        String dir = reflectGetModelDir();
        if (dir == null || dir.isEmpty()) {
            File c = new File(".");
            return c.toPath().resolve("vmc_mapping.json");
        }
        return Paths.get(dir, "vmc_mapping.json");
    }

    private VMCMapping findMapping(String bone, String joint) {
        for (var e : mappingList.children()) {
            if (Objects.equals(e.mapping.vmcBone, bone) && Objects.equals(e.mapping.urdfJoint, joint))
                return e.mapping;
        }
        return null;
    }

    // ==================== Lists & widgets =====================

    private void rebuildBoneList() {
        boneList.children().clear();
        // try live bones, else fallback list
        Map<String, Object> bones = reflectCollectBoneMap(reflectGetVmcState());
        if (bones.isEmpty()) {
            for (String b : FALLBACK_VRM_BONES) {
                boneList.children().add(new BoneList.Entry(b, () -> {
                    selectedBone = b; status = "선택: " + b;
                }));
            }
        } else {
            ArrayList<String> names = new ArrayList<>(bones.keySet());
            names.sort(String.CASE_INSENSITIVE_ORDER);
            for (String b : names) {
                boneList.children().add(new BoneList.Entry(b, () -> {
                    selectedBone = b; status = "선택: " + b;
                }));
            }
        }
    }

    private void rebuildJointList() {
        jointList.children().clear();
        model = reflectGetRobotModel(); // refresh
        if (model == null || model.joints == null) return;
        for (URDFJoint j : model.joints) {
            final String jname = j.name != null ? j.name : "(unnamed)";
            jointList.children().add(new JointList.Entry(jname, () -> {
                selectedJoint = jname;
                selectedJointObj = j;
                // slider range
                float lo = (j.limit != null) ? (float) j.limit.lower : -3.14159f;
                float hi = (j.limit != null) ? (float) j.limit.upper : 3.14159f;
                jointSlider.setRange(lo, hi);
                // default axis from joint
                float[] axis = reflectGetJointAxis(j);
                if (axis != null) { ax = axis[0]; ay = axis[1]; az = axis[2]; normAxis(); }
                status = "선택: " + selectedJoint;
            }, j));
        }
    }

    private void drawSeries(GuiGraphics g, int x, int y, int w, int h, List<Float> data, int color) {
        if (data.size() < 2) return;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : data) { if (v<min) min=v; if (v>max) max=v; }
        if (Math.abs(max-min) < 1e-6f) { max = min + 1e-3f; }
        float prevX = x, prevY = y + h - (data.get(0)-min)/(max-min)*h;
        for (int i=1;i<data.size();i++) {
            float nx = x + (i * (w-2f) / (data.size()-1));
            float ny = y + h - (data.get(i)-min)/(max-min)*h;
            g.hLine((int)prevX, (int)nx, (int)prevY, color);
            prevX = nx; prevY = ny;
        }
    }

    private static class BoneList extends ObjectSelectionList<BoneList.Entry> {
        final int left;
        public BoneList(Minecraft mc, int width, int height, int top, int left) {
            super(mc, width, height, top, top + height);
            this.left = left;
        }
        @Override public int getRowWidth() { return this.width - 8; }
        @Override public int getRowLeft()  { return this.left + 4; }
        @Override public int getX()        { return this.left; }
        @Override public int getRowHeight() { return 16; }
        static class Entry extends ObjectSelectionList.Entry<Entry> {
            final String name; final Runnable cb;
            Entry(String n, Runnable cb) { this.name=n; this.cb=cb; }
            @Override public void render(GuiGraphics g, int idx, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTicks) {
                g.drawString(Minecraft.getInstance().font, name, left+2, top+2, 0xFFFFFFFF, false);
            }
            @Override public boolean mouseClicked(double mx, double my, int btn) {
                if (btn==0) { cb.run(); this.select(); return true; }
                return false;
            }
            @Override public Component getNarration() { return Component.literal(name); }
        }
    }

    private static class JointList extends ObjectSelectionList<JointList.Entry> {
        final int left;
        public JointList(Minecraft mc, int width, int height, int top, int left) {
            super(mc, width, height, top, top + height);
            this.left = left;
        }
        @Override public int getRowWidth() { return this.width - 8; }
        @Override public int getRowLeft()  { return this.left + 4; }
        @Override public int getX()        { return this.left; }
        @Override public int getRowHeight() { return 16; }

        static class Entry extends ObjectSelectionList.Entry<Entry> {
            final String name;
            final Runnable cb;
            final URDFJoint joint;
            Entry(String name, Runnable cb, URDFJoint j) { this.name=name; this.cb=cb; this.joint=j; }
            @Override public void render(GuiGraphics g, int idx, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float pt) {
                String lim = "";
                if (joint != null) {
                    float cur = (float) (joint.currentPosition);
                    float lo  = joint.limit != null ? (float)joint.limit.lower : Float.NaN;
                    float hi  = joint.limit != null ? (float)joint.limit.upper : Float.NaN;
                    String curS = String.format(Locale.ROOT, "θ=%.2f°", Math.toDegrees(cur));
                    String limS = (Float.isFinite(lo) && Float.isFinite(hi))
                            ? String.format(Locale.ROOT, " [%d°, %d°]", Math.round(Math.toDegrees(lo)), Math.round(Math.toDegrees(hi)))
                            : " [free]";
                    lim = "  " + curS + limS;
                }
                g.drawString(Minecraft.getInstance().font, name + lim, left + 2, top + 2, 0xFFFFFFFF, false);
            }
            @Override public boolean mouseClicked(double mx, double my, int btn) {
                if (btn==0) { cb.run(); this.select(); return true; }
                return false;
            }
            @Override public Component getNarration() { return Component.literal(name); }
        }
    }

    private static class MappingList extends ObjectSelectionList<MappingList.Entry> {
        final int left;
        private boolean dirty = false;
        public MappingList(Minecraft mc, int width, int height, int top, int left) {
            super(mc, width, height, top, top + height);
            this.left = left;
        }
        @Override public int getRowWidth() { return this.width - 8; }
        @Override public int getRowLeft()  { return this.left + 4; }
        @Override public int getX()        { return this.left; }
        @Override public int getRowHeight(){ return 16; }
        public void setDirty() { this.dirty = true; }
        public Entry getSelected() { return super.getSelected(); }
        @Override public void render(GuiGraphics g, int mx, int my, float pt) {
            if (dirty) { // re-render text on demand
                dirty = false;
            }
            super.render(g, mx, my, pt);
        }
        static class Entry extends ObjectSelectionList.Entry<Entry> {
            final MappingList owner; final VMCMapping mapping;
            Entry(VMCMapping m, MappingList owner){ this.mapping=m; this.owner=owner; }
            @Override
            public void render(GuiGraphics g, int idx, int top, int left, int width, int height, int mx, int my, boolean hovered, float pt) {
                g.drawString(Minecraft.getInstance().font, "• " + mapping.label(), left+2, top+2, 0xFFECECEC, false);
            }
            @Override public boolean mouseClicked(double mx, double my, int btn) {
                if (btn==0) { owner.setSelected(this); return true; }
                return false;
            }
            @Override public Component getNarration() { return Component.literal(mapping.label()); }
        }
    }

    /** simple horizontal slider to drive selected joint (1.20: no AbstractSliderButton, so hand-rolled) */
    private static class JointScrubSlider extends Button {
        private float value, min, max;
        private final SliderListener listener;
        private boolean dragging = false;
        public interface SliderListener { void onValue(float v); }
        public JointScrubSlider(int x, int y, int w, int h, float min, float max, SliderListener l) {
            super(x, y, w, h, Component.empty(), b->{} , DEFAULT_NARRATION);
            this.listener = l; this.min = min; this.max = max; this.value = 0f;
            setMessage(label());
        }
        public void setRange(float min, float max){ this.min=min; this.max=max; setMessage(label()); }
        public void setValue(float v){ this.value = clamp(v); setMessage(label()); }
        private float clamp(float v){ return Math.max(min, Math.min(max, v)); }
        private Component label(){ return Component.literal(String.format(Locale.ROOT,"Angle: %.2f°", Math.toDegrees(value))); }
        @Override public void onClick(double mouseX, double mouseY) { dragging = true; setFromMouse(mouseX); }
        @Override protected void onDrag(double x, double y, double dx, double dy) { setFromMouse(x); }
        @Override public void onRelease(double x, double y) { dragging = false; }
        private void setFromMouse(double mx) {
            float t = (float)((mx - getX()) / (float)getWidth()); t = Math.max(0, Math.min(1, t));
            value = min + (max - min) * t; setMessage(label()); if (listener != null) listener.onValue(value);
        }
    }

    // ============== VMC value extraction & mapping apply ==============

    private float[] axisForCurrent() {
        float nx = ax, ny = ay, nz = az;
        float len = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
        if (len < 1e-6f) { // fallback to joint axis if possible
            if (selectedJointObj != null) {
                float[] a = reflectGetJointAxis(selectedJointObj);
                if (a != null) { nx = a[0]; ny=a[1]; nz=a[2]; len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz); }
            }
        }
        if (len < 1e-6f) { nx=0; ny=1; nz=0; }
        return new float[]{ nx/len, ny/len, nz/len };
    }

    private float extractValue(VMCMapping.ExtractionMode mode, String component, Object tr) {
        Quaternionf q = reflectGetQuat(tr);
        if (q == null) return 0f;
        switch (mode) {
            case QUATERNION_ANGLE: {
                float w = q.w(); double ang = 2.0 * Math.acos(Math.max(-1.0, Math.min(1.0, w)));
                return (float)ang;
            }
            case EULER_X: case EULER_Y: case EULER_Z: {
                Vector3f e = new Vector3f();
                new Quaternionf(q).getEulerAnglesXYZ(e); // X, Y, Z
                return switch (mode) {
                    case EULER_X -> e.x;
                    case EULER_Y -> e.y;
                    case EULER_Z -> e.z;
                    default -> 0f;
                };
            }
            case AXIS_PROJECTION: {
                float[] a = axisForCurrent();
                float dot = q.x()*a[0] + q.y()*a[1] + q.z()*a[2];
                return (float)(2.0 * Math.atan2(dot, q.w()));
            }
        }
        return 0f;
    }

    private void applyCurrentMappings(Object vmcStateObj) {
        Map<String, Object> bones = reflectCollectBoneMap(vmcStateObj);
        for (var entry : mappingList.children()) {
            VMCMapping m = entry.mapping;
            Object tr = bones.get(m.vmcBone);
            if (tr == null) continue;

            float val;
            if (m.mode == VMCMapping.ExtractionMode.AXIS_PROJECTION) {
                float len = (float)Math.sqrt(m.ax*m.ax + m.ay*m.ay + m.az*m.az);
                float axN = len > 1e-6f ? m.ax/len : 0f;
                float ayN = len > 1e-6f ? m.ay/len : 1f;
                float azN = len > 1e-6f ? m.az/len : 0f;
                Quaternionf q = reflectGetQuat(tr);
                float dot = q.x()*axN + q.y()*ayN + q.z()*azN;
                val = (float)(2.0 * Math.atan2(dot, q.w()));
            } else {
                val = extractValue(m.mode, m.component, tr);
            }
            float out = val * m.multiplier + m.offset;

            // clamp to joint limit
            URDFJoint j = model != null ? model.findJointByName(m.urdfJoint) : null;
            if (j != null && j.limit != null && j.limit.upper > j.limit.lower) {
                float lo = (float)j.limit.lower, hi = (float)j.limit.upper;
                if (out < lo) out = lo;
                if (out > hi) out = hi;
            }
            reflectSetJointPreview(m.urdfJoint, out);
        }
    }

    private float[] fitLine(List<Float> x, List<Float> y) {
        double sx=0, sy=0, sxx=0, sxy=0;
        int n = Math.min(x.size(), y.size());
        for (int i=0;i<n;i++) { float xi=x.get(i), yi=y.get(i); sx+=xi; sy+=yi; sxx+=xi*xi; sxy+=xi*yi; }
        double denom = n>1 ? (n*sxx - sx*sx) : 1.0;
        double m = denom!=0 ? (n* sxy - sx*sy) / denom : 0.0;
        double c = (sy - m*sx)/ Math.max(1.0, n);
        return new float[]{ (float)m, (float)c };
    }

    // ============== Reflection helpers (renderer & VMC) ==============

    private URDFRobotModel reflectGetRobotModel() {
        try {
            if (renderer == null) return null;
            Class<?> c = renderer.getClass();
            Method m = null;
            try { m = c.getMethod("getRobotModel"); }
            catch (NoSuchMethodException ignore) {
                // fallback to field
                try { Field f = c.getDeclaredField("robotModel"); f.setAccessible(true); return (URDFRobotModel) f.get(renderer); }
                catch (Throwable ignored) {}
            }
            if (m != null) return (URDFRobotModel)m.invoke(renderer);
        } catch (Throwable ignored) { }
        return null;
    }

    private void reflectSetJointPreview(String name, float v) {
        try {
            if (renderer == null) return;
            Method m = null;
            try { m = renderer.getClass().getMethod("setJointPreview", String.class, float.class); }
            catch (NoSuchMethodException ex) {
                // optional legacy method name
                try { m = renderer.getClass().getMethod("setJoint", String.class, float.class); } catch (Throwable ignored2) {}
            }
            if (m != null) m.invoke(renderer, name, v);
        } catch (Throwable t) {
            // ignore
        }
    }

    private String reflectGetModelDir() {
        try {
            if (renderer == null) return null;
            Method m;
            try { m = renderer.getClass().getMethod("GetModelDir"); }
            catch (NoSuchMethodException e) { m = renderer.getClass().getMethod("getModelDir"); }
            Object r = m.invoke(renderer); return r != null ? r.toString() : null;
        } catch (Throwable ignored) { return null; }
    }

    private Object reflectGetVmcState() {
        try {
            Class<?> c = Class.forName("top.fifthlight.armorstand.vmc.VmcMarionetteManager");
            Method m = c.getMethod("getState");
            return m.invoke(null);
        } catch (Throwable ignored) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reflectCollectBoneMap(Object vmcState) {
        Map<String, Object> map = new HashMap<>();
        if (vmcState == null) return map;
        try {
            Field f = vmcState.getClass().getField("boneTransforms");
            Object raw = f.get(vmcState);
            if (!(raw instanceof Map)) return map;
            Map<Object,Object> src = (Map<Object, Object>) raw;
            for (var e : src.entrySet()) {
                Object tag = e.getKey();
                String name = tag != null ? tag.toString() : "(null)";
                try {
                    Method nm = tag.getClass().getMethod("name");
                    Object v = nm.invoke(tag);
                    if (v != null) name = v.toString();
                } catch (Throwable ignored) {}
                map.put(name, e.getValue());
            }
        } catch (Throwable ignored) {}
        return map;
    }

    private Quaternionf reflectGetQuat(Object tr) {
        if (tr == null) return null;
        try {
            Object rot = tr.getClass().getField("rotation").get(tr);
            float x = (Float)rot.getClass().getMethod("x").invoke(rot);
            float y = (Float)rot.getClass().getMethod("y").invoke(rot);
            float z = (Float)rot.getClass().getMethod("z").invoke(rot);
            float w = (Float)rot.getClass().getMethod("w").invoke(rot);
            return new Quaternionf(x,y,z,w);
        } catch (Throwable ignored) { return null; }
    }
    private Float reflectGetJointAngle(URDFJoint j) {
        try { return (float) j.currentPosition; } catch (Throwable t) { return null; }
    }
    private float[] reflectGetJointAxis(URDFJoint j) {
        try {
            if (j == null || j.axis == null) return null;
            Field axisField = j.getClass().getField("axis");
            Object axObj = axisField.get(j); if (axObj == null) return null;
            Field axF = axObj.getClass().getField("x");
            Field ayF = axObj.getClass().getField("y");
            Field azF = axObj.getClass().getField("z");
            float ax = ((Number)axF.get(axObj)).floatValue();
            float ay = ((Number)ayF.get(axObj)).floatValue();
            float az = ((Number)azF.get(axObj)).floatValue();
            return new float[]{ ax, ay, az };
        } catch (Throwable ignored) { return null; }
    }

    private void normAxis() {
        float n = (float)Math.sqrt(ax*ax + ay*ay + az*az);
        if (n < 1e-6f) { ax=0; ay=1; az=0; } else { ax/=n; ay/=n; az/=n; }
    }
}
