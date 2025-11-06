package com.kAIS.KAIMyEntity.vrm;

import com.google.gson.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * VrmLoader: VRM/GLB/GLTF에서 "스켈레톤(본 트리)"만 추출하는 경량 로더
 * - 외부 렌더/메시 없이, 스틱맨/프리뷰가 쓸 본(이름/부모자식/변환)만 제공합니다.
 *
 * 지원 포맷:
 *  - VRM 0.x: extensions.VRM.humanoid.humanBones[]
 *  - VRM 1.0: extensions.VRMC_vrm.humanoid.humanBones.{bone}.node
 *  - GLB/GLTF 기본 nodes/skins (humanoid 없으면 폴백)
 */
public final class VrmLoader {

    // === 결과 구조 ===
    public static final class VrmSkeleton {
        public final List<Bone> bones = new ArrayList<>();                // 선택된 본(보통 humanoid)
        public final Map<String, Bone> byName = new HashMap<>();          // 이름 → 본
        public final List<Node> allNodes = new ArrayList<>();             // glTF의 전체 노드
        public final Map<Integer, Integer> parentOf = new HashMap<>();    // nodeIndex → parentIndex
        public final String profile;                                       // "VRM0" / "VRM1" / "GLTF"
        public final String sourcePath;

        VrmSkeleton(String profile, String sourcePath) {
            this.profile = profile; this.sourcePath = sourcePath;
        }

        public Bone getBone(String name) { return byName.get(name); }
    }

    public static final class Bone {
        public final String name;
        public final int nodeIndex;            // glTF node index
        public final Integer parentNodeIndex;  // null이면 루트
        public final List<Integer> children = new ArrayList<>();
        public final Vector3f translation = new Vector3f(0,0,0);
        public final Quaternionf rotation = new Quaternionf(0,0,0,1);
        public final Vector3f scale = new Vector3f(1,1,1);

        Bone(String name, int nodeIndex, Integer parentNodeIndex) {
            this.name = name; this.nodeIndex = nodeIndex; this.parentNodeIndex = parentNodeIndex;
        }
    }

    public static final class Node {
        public final int index;
        public final String name;
        public final List<Integer> children = new ArrayList<>();
        public final Vector3f translation = new Vector3f(0,0,0);
        public final Quaternionf rotation = new Quaternionf(0,0,0,1);
        public final Vector3f scale = new Vector3f(1,1,1);
        Node(int index, String name) { this.index = index; this.name = name; }
    }

    private VrmLoader() {}

    // === Public API ===

    /** VRM/GLB/GLTF 파일에서 스켈레톤을 읽어옵니다. 실패 시 null */
    public static VrmSkeleton load(File file) {
        if (file == null || !file.exists()) return null;
        try {
            String lower = file.getName().toLowerCase(Locale.ROOT);
            String json;
            if (lower.endsWith(".glb") || lower.endsWith(".vrm")) {
                json = readGlbJson(file);
            } else if (lower.endsWith(".gltf")) {
                json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } else {
                return null;
            }
            if (json == null) return null;
            return parseGltfJson(json, file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[VrmLoader] load failed: " + e);
            return null;
        }
    }

    // === GLB(JSON) 로드 ===
    private static String readGlbJson(File file) throws IOException {
        byte[] buf = Files.readAllBytes(file.toPath());
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.remaining() < 12) return null;
        int magic = bb.getInt();   // 0x46546C67 'glTF'
        int version = bb.getInt(); // 2
        int length = bb.getInt();
        if (magic != 0x46546C67) return null;

        String json = null;
        while (bb.remaining() >= 8) {
            int chunkLen = bb.getInt();
            int chunkType = bb.getInt(); // 0x4E4F534A 'JSON', 0x004E4942 'BIN\0'
            if (chunkLen < 0 || chunkLen > bb.remaining()) break;
            byte[] chunk = new byte[chunkLen];
            bb.get(chunk);
            if (chunkType == 0x4E4F534A) { // JSON
                json = new String(chunk, StandardCharsets.UTF_8);
                break; // JSON만 필요
            }
        }
        return json;
    }

    // === glTF JSON 파싱 & 스켈레톤 추출 ===
    private static VrmSkeleton parseGltfJson(String json, String sourcePath) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // nodes
        List<Node> nodes = parseNodes(root.getAsJsonArray("nodes"));

        // parent map 만들기
        Map<Integer, Integer> parentOf = buildParentMap(nodes);

        // VRM1 시도 → VRM0 시도 → 폴백(GLTF 노드)
        Map<String, Integer> humanMap = extractHumanoidMapVrm1(root);
        String profile = "VRM1";
        if (humanMap == null || humanMap.isEmpty()) {
            humanMap = extractHumanoidMapVrm0(root);
            profile = (humanMap == null || humanMap.isEmpty()) ? "GLTF" : "VRM0";
        }

        VrmSkeleton skel = new VrmSkeleton(profile, sourcePath);
        skel.allNodes.addAll(nodes);
        skel.parentOf.putAll(parentOf);

        if (humanMap != null && !humanMap.isEmpty()) {
            // 선택된 humanoid 본만 골라서 뼈 리스트 구성
            for (Map.Entry<String,Integer> e : humanMap.entrySet()) {
                int idx = e.getValue();
                if (idx < 0 || idx >= nodes.size()) continue;
                Node n = nodes.get(idx);
                Integer parent = parentOf.get(idx);
                Bone b = new Bone(e.getKey(), idx, parent);
                b.translation.set(n.translation);
                b.rotation.set(n.rotation);
                b.scale.set(n.scale);
                b.children.addAll(n.children);
                skel.bones.add(b);
                skel.byName.put(b.name, b);
            }
        } else {
            // 폴백: 모든 nodes를 본처럼 취급 (이름 없는 노드는 "node_i")
            for (Node n : nodes) {
                String name = (n.name != null && !n.name.isEmpty()) ? n.name : ("node_" + n.index);
                Integer parent = parentOf.get(n.index);
                Bone b = new Bone(name, n.index, parent);
                b.translation.set(n.translation);
                b.rotation.set(n.rotation);
                b.scale.set(n.scale);
                b.children.addAll(n.children);
                skel.bones.add(b);
                skel.byName.put(b.name, b);
            }
        }
        return skel;
    }

    private static List<Node> parseNodes(JsonArray arr) {
        List<Node> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            JsonObject o = arr.get(i).getAsJsonObject();
            String name = optString(o, "name", "");
            Node n = new Node(i, name);

            // children
            JsonArray ch = o.getAsJsonArray("children");
            if (ch != null) for (int k = 0; k < ch.size(); k++) n.children.add(ch.get(k).getAsInt());

            // TRS
            JsonArray t = o.getAsJsonArray("translation");
            if (t != null && t.size() == 3) n.translation.set(t.get(0).getAsFloat(), t.get(1).getAsFloat(), t.get(2).getAsFloat());
            JsonArray r = o.getAsJsonArray("rotation");
            if (r != null && r.size() == 4) n.rotation.set(r.get(0).getAsFloat(), r.get(1).getAsFloat(), r.get(2).getAsFloat(), r.get(3).getAsFloat());
            JsonArray s = o.getAsJsonArray("scale");
            if (s != null && s.size() == 3) n.scale.set(s.get(0).getAsFloat(), s.get(1).getAsFloat(), s.get(2).getAsFloat());

            out.add(n);
        }
        return out;
    }

    private static Map<Integer, Integer> buildParentMap(List<Node> nodes) {
        Map<Integer, Integer> parentOf = new HashMap<>();
        for (Node n : nodes) for (int c : n.children) parentOf.put(c, n.index);
        return parentOf;
    }

    // === VRM 1.0: extensions.VRMC_vrm.humanoid.humanBones.{bone}.node ===
    private static Map<String, Integer> extractHumanoidMapVrm1(JsonObject root) {
        try {
            JsonObject ext = obj(root, "extensions");
            if (ext == null) return null;
            JsonObject vrmc = obj(ext, "VRMC_vrm");
            if (vrmc == null) return null;
            JsonObject humanoid = obj(vrmc, "humanoid");
            if (humanoid == null) return null;
            JsonObject humanBones = obj(humanoid, "humanBones");
            if (humanBones == null) return null;

            Map<String, Integer> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : humanBones.entrySet()) {
                String bone = e.getKey(); // e.g., "hips","leftUpperArm"
                JsonObject bo = e.getValue().getAsJsonObject();
                if (bo.has("node")) {
                    int idx = bo.get("node").getAsInt();
                    map.put(bone, idx);
                }
            }
            return map;
        } catch (Exception ignore) { return null; }
    }

    // === VRM 0.x: extensions.VRM.humanoid.humanBones[] with {bone,node} ===
    private static Map<String, Integer> extractHumanoidMapVrm0(JsonObject root) {
        try {
            JsonObject ext = obj(root, "extensions");
            if (ext == null) return null;
            JsonObject vrm = obj(ext, "VRM");
            if (vrm == null) return null;
            JsonObject humanoid = obj(vrm, "humanoid");
            if (humanoid == null) return null;
            JsonArray arr = arr(humanoid, "humanBones");
            if (arr == null) return null;

            Map<String, Integer> map = new LinkedHashMap<>();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject hb = arr.get(i).getAsJsonObject();
                String bone = optString(hb, "bone", null);
                if (bone == null) continue;
                if (hb.has("node")) {
                    int idx = hb.get("node").getAsInt();
                    map.put(bone, idx);
                }
            }
            return map;
        } catch (Exception ignore) { return null; }
    }

    // === JSON helpers ===
    private static JsonObject obj(JsonObject o, String k) {
        if (o == null || !o.has(k) || !o.get(k).isJsonObject()) return null;
        return o.getAsJsonObject(k);
    }
    private static JsonArray arr(JsonObject o, String k) {
        if (o == null || !o.has(k) || !o.get(k).isJsonArray()) return null;
        return o.getAsJsonArray(k);
    }
    private static String optString(JsonObject o, String k, String def) {
        try { return o.get(k).getAsString(); } catch (Exception e) { return def; }
    }
}
