package com.kAIS.KAIMyEntity.vrm.render;

import com.kAIS.KAIMyEntity.renderer.IMMDModel;
import com.kAIS.KAIMyEntity.vrm.VrmLoader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/** VRM 전용: 스켈레톤 스틱맨 프리뷰 렌더러(가벼움) */
public class VRMModelOpenGL implements IMMDModel {

    private final String modelDir;
    private VrmLoader.VrmSkeleton skel;                     // 원본 스켈레톤
    private final Map<String, Pose> live = new HashMap<>(); // 실시간 포즈(VMC 적용)
    private float viewScale = 0.01f;                        // m → 뷰 스케일(상황에 맞게 조절)

    private static final class Pose {
        final Vector3f p = new Vector3f();   // position (m)
        final Quaternionf q = new Quaternionf(); // rotation
    }

    public VRMModelOpenGL(String modelDir) {
        this.modelDir = modelDir;
    }

    /** 선택: 매니저에서 팩토리처럼 부르고 싶을 때 */
    public static IMMDModel Create(String modelDir) {
        return new VRMModelOpenGL(modelDir);
    }

    // ---------------- IMMDModel ----------------

    @Override
    public void Render(Entity entityIn, float entityYaw, float entityPitch,
                       Vector3f entityTrans, float tickDelta, PoseStack mat, int packedLight) {
        // 레거시 경로는 사용하지 않음(빈 구현)
    }

    @Override public void ChangeAnim(long anim, long layer) {}
    @Override public void ResetPhysics() {}
    @Override public long GetModelLong() { return 0L; }
    @Override public String GetModelDir() { return modelDir; }
    @Override public ResourceLocation getTexture() { return null; }

    @Override
    public void setPreviewSkeleton(Object skeleton) {
        if (!(skeleton instanceof VrmLoader.VrmSkeleton s)) return;
        this.skel = s;
        live.clear();
        // 초기 포즈 세팅
        for (var b : s.bones) {
            Pose p = new Pose();
            p.p.set(b.translation);
            p.q.set(b.rotation);
            live.put(b.name, p);
        }
    }

    /** VMC 상태를 읽어 본 포즈 갱신(리플렉션) */
    @Override
    public void Update(float deltaTime) {
        if (skel == null) return;
        Object vmc = reflectGetVmcState();
        if (vmc == null) return;
        Map<String, Object> map = reflectCollectBoneMap(vmc);
        for (var e : map.entrySet()) {
            Pose lp = live.get(e.getKey());
            if (lp == null) continue;
            Object tr = e.getValue();
            Vector3f pos = readPos(tr);
            Quaternionf rot = readQuat(tr);
            if (pos != null) lp.p.set(pos);
            if (rot != null) lp.q.set(rot);
        }
    }

    /** 새 파이프라인: VRM 스틱맨(부모-자식 라인) 그리기 */
    @Override
    public void renderToBuffer(Entity entityIn,
                               float entityYaw, float entityPitch, Vector3f entityTrans, float tickDelta,
                               PoseStack pose,
                               VertexConsumer vtx,
                               int packedLight,
                               int overlay) {
        if (skel == null) return;

        pose.pushPose();
        // 미니뷰 위치/스케일 — 상황 맞게 조정
        pose.translate(entityTrans.x(), entityTrans.y(), entityTrans.z());
        pose.scale(viewScale, viewScale, viewScale);

        Matrix4f m = toMojMatrix(pose.last().pose()); // JOML→MC 변환 유틸

        int col = 0xFFFFFFFF; // 흰색
        for (var b : skel.bones) {
            if (b.parentNodeIndex == null) continue;

            String child = b.name;
            String parent = findParentName(skel, b);
            if (parent == null) continue;

            Pose cp = live.get(child);
            Pose pp = live.get(parent);
            if (cp == null || pp == null) continue;

            addSegment(vtx, m, pp.p, cp.p, col, packedLight, overlay);
        }

        pose.popPose();
    }

    // ---------------- 라인(세그먼트) 유틸 ----------------

    private static void addSegment(VertexConsumer v, Matrix4f m,
                                   Vector3f a, Vector3f b, int argb, int light, int overlay) {
        float rf = ((argb >> 16) & 255) / 255f;
        float gf = ((argb >> 8)  & 255) / 255f;
        float bf = ( argb        & 255) / 255f;
        float af = ((argb >>> 24) & 255) / 255f;

        // 간단 프리뷰: 두 점만 찍어도 보이지만, 얇은 리본(2삼각형)을 추천
        // 여기서는 초간단하게 점 두 개(엔진에 따라 라인으로 보일 수 있음)
        v.vertex(m, a.x, a.y, a.z).color(rf,gf,bf,af).uv2(light).overlayCoords(overlay).normal(0,1,0).endVertex();
        v.vertex(m, b.x, b.y, b.z).color(rf,gf,bf,af).uv2(light).overlayCoords(overlay).normal(0,1,0).endVertex();
    }

    private static Matrix4f toMojMatrix(com.mojang.math.Matrix4f mm) {
        Matrix4f out = new Matrix4f();
        out.m00(mm.m00()); out.m01(mm.m01()); out.m02(mm.m02()); out.m03(mm.m03());
        out.m10(mm.m10()); out.m11(mm.m11()); out.m12(mm.m12()); out.m13(mm.m13());
        out.m20(mm.m20()); out.m21(mm.m21()); out.m22(mm.m22()); out.m23(mm.m23());
        out.m30(mm.m30()); out.m31(mm.m31()); out.m32(mm.m32()); out.m33(mm.m33());
        return out;
    }

    private String findParentName(VrmLoader.VrmSkeleton s, VrmLoader.Bone b) {
        Integer pi = b.parentNodeIndex;
        if (pi == null) return null;
        for (var x : s.bones) if (Objects.equals(x.nodeIndex, pi)) return x.name;
        if (pi >= 0 && pi < s.allNodes.size()) return s.allNodes.get(pi).name;
        return null;
    }

    // ---------------- VMC 리플렉션(당신 프로젝트 유틸과 동일) ----------------

    private Object reflectGetVmcState() {
        try {
            Class<?> mgr = Class.forName("top.fifthlight.armorstand.vmc.VmcMarionetteManager");
            var getState = mgr.getMethod("getState");
            return getState.invoke(null);
        } catch (Throwable ignored) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reflectCollectBoneMap(Object vmcState) {
        Map<String, Object> out = new HashMap<>();
        if (vmcState == null) return out;
        try {
            var f = vmcState.getClass().getField("boneTransforms");
            Object raw = f.get(vmcState);
            if (!(raw instanceof Map<?,?> m)) return out;
            for (var e : ((Map<Object,Object>)m).entrySet()) {
                Object tag = e.getKey();
                String name = tag.toString();
                try { var nameM = tag.getClass().getMethod("name"); Object n = nameM.invoke(tag); if (n!=null) name=n.toString(); } catch (Throwable ignored) {}
                out.put(name, e.getValue());
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private Vector3f readPos(Object tr) {
        try {
            Object p = tr.getClass().getField("position").get(tr);
            float x = (float)p.getClass().getMethod("x").invoke(p);
            float y = (float)p.getClass().getMethod("y").invoke(p);
            float z = (float)p.getClass().getMethod("z").invoke(p);
            return new Vector3f(x,y,z);
        } catch (Throwable ignored) { return null; }
    }

    private Quaternionf readQuat(Object tr) {
        try {
            Object r = tr.getClass().getField("rotation").get(tr);
            float x = (float)r.getClass().getMethod("x").invoke(r);
            float y = (float)r.getClass().getMethod("y").invoke(r);
            float z = (float)r.getClass().getMethod("z").invoke(r);
            float w = (float)r.getClass().getMethod("w").invoke(r);
            return new Quaternionf(x,y,z,w);
        } catch (Throwable ignored) { return null; }
    }
}
