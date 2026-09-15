package com.adaiadai.core.domain.learn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * LearnCardPages — 卡片「页序列」的解析与渲染（2026-09-15 卡片流批）。
 * <p>
 * <b>落在哪</b>：卡片 md 正文里的一个独立段 {@code ## 卡片页}，段内容是 fenced JSON 数组。
 * <ul>
 *   <li><b>不动原有四段</b>（核心观点/关键要点/我的疑问/复述）→ 旧卡、Mac 侧技能产物、
 *       问答召回、编辑/状态流转全部零影响；</li>
 *   <li><b>没有该段 = 老卡</b> → {@link #parse} 返回空列表 → 渲染端降级为原有形态；</li>
 *   <li><b>解析失败也只丢页</b>，绝不让卡片落盘或读取失败（素材是资产，页是呈现层）。</li>
 * </ul>
 * <p>
 * <b>为什么用 JSON 而不是更花哨的 md</b>：写入方是 LLM（本来就产 JSON）、读取方是前后端，
 * 手写解析器要面对的字段可选/类型漂移问题，JSON + 逐字段容错最稳；格式真相仍在文件里（File First）。
 */
public final class LearnCardPages {

    /** 段名（md 里的 {@code ## 卡片页}）。 */
    public static final String SECTION = "卡片页";
    private static final String FENCE_LANG = "json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 单卡页数上限（防 LLM 刷页把卡片撑成小作文）。 */
    public static final int MAX_PAGES = 24;
    private static final int MAX_LINE = 400;
    private static final int MAX_ITEMS = 12;

    private LearnCardPages() {}

    // ── 读：md 正文 → 页序列 ──

    /** 从去 frontmatter 的正文里解析页序列；无段/坏段/空段一律返回空列表。 */
    public static List<LearnPage> parse(String body) {
        String raw = extractSection(body);
        if (raw == null || raw.isBlank()) return List.of();
        return parseJson(stripFence(raw));
    }

    /** 解析页数组 JSON（LLM 直出的 pages 字段也走这里）；坏 JSON → 空列表（降级不报错）。 */
    public static List<LearnPage> parseJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) return List.of();
            List<LearnPage> out = new ArrayList<>();
            for (JsonNode n : root) {
                if (out.size() >= MAX_PAGES) break;
                LearnPage page = toPage(n);
                if (page != null) out.add(page);
            }
            return List.copyOf(out);
        } catch (Exception e) {
            // 呈现层出问题不该让卡片读不出来：静默降级为空（调用方回落旧形态）
            return List.of();
        }
    }

    /**
     * 从 AI 原文里**尽力**取出页数组（2026-09-15 回填批）：容忍 ```json 围栏、前后废话、
     * 只给单个页对象、以及「数组被别的 JSON 包着」这几种常见走样。
     * <p>
     * 为什么需要它：回填路径让模型直接吐数组（不是像消化那样吐一个大对象），
     * 而给对象设计的「取最外层花括号」在这里会把 `[{…},{…}]` 切成两段非法 JSON。
     */
    public static List<LearnPage> parseLenient(String text) {
        if (text == null || text.isBlank()) return List.of();
        String t = stripFence(text.strip());
        List<LearnPage> direct = parseJson(t);
        if (!direct.isEmpty()) return direct;
        // 退一步：最外层方括号范围
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start >= 0 && end > start) {
            List<LearnPage> arr = parseJson(t.substring(start, end + 1));
            if (!arr.isEmpty()) return arr;
        }
        // 再退一步：只给了一个页对象
        int objStart = t.indexOf('{');
        int objEnd = t.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return parseJson("[" + t.substring(objStart, objEnd + 1) + "]");
        }
        return List.of();
    }

    /** 取 {@code ## 卡片页} 段原文（不含段标题行）；段标题允许带序号/括号后缀。 */
    static String extractSection(String body) {
        if (body == null || body.isBlank()) return null;
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (in) break;                                  // 下一段开始，本段结束
                in = SECTION.equals(normalizeHeader(line.substring(3)));
                continue;
            }
            if (in) sb.append(line).append("\n");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 段名归一：去 `一、`/`1.` 序号前缀与 `（…）`/`(...)` 后缀（与仓储读侧同口径）。 */
    public static String normalizeHeader(String header) {
        String h = header == null ? "" : header.strip();
        h = h.replaceFirst("^[0-9]+\\s*[.、]\\s*", "");
        h = h.replaceFirst("^[一二三四五六七八九十]+\\s*[、.]\\s*", "");
        int cut = h.indexOf('（');
        int cut2 = h.indexOf('(');
        if (cut < 0 || (cut2 >= 0 && cut2 < cut)) cut = cut2;
        return (cut > 0 ? h.substring(0, cut) : h).strip();
    }

    /** 去掉 ```json … ``` 围栏（LLM 有时带、有时不带）。 */
    static String stripFence(String s) {
        String t = s == null ? "" : s.strip();
        if (!t.startsWith("```")) return t;
        int nl = t.indexOf('\n');
        if (nl > 0) t = t.substring(nl + 1);
        int end = t.lastIndexOf("```");
        if (end >= 0) t = t.substring(0, end);
        return t.strip();
    }

    private static LearnPage toPage(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        LearnPage page = new LearnPage(
                text(n, "kind"),
                clip(text(n, "title")),
                clip(text(n, "claim")),
                stringList(n.get("bullets")),
                table(n.get("table")),
                numbers(n.get("numbers")),
                side(n.get("left"), "bad"),
                side(n.get("right"), "good"),
                nodes(n.get("nodes")));
        return page.isEmpty() ? null : page;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || !v.isValueNode() ? "" : v.asText("");
    }

    private static String clip(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() <= MAX_LINE ? t : t.substring(0, MAX_LINE);
    }

    private static List<String> stringList(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (out.size() >= MAX_ITEMS) break;
            if (!item.isValueNode()) continue;
            String s = clip(item.asText(""));
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private static List<LearnPage.NumberCell> numbers(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<LearnPage.NumberCell> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (out.size() >= MAX_ITEMS) break;
            if (!item.isObject()) continue;
            String v = clip(text(item, "v"));
            String l = clip(text(item, "l"));
            if (v.isBlank() && l.isBlank()) continue;
            out.add(new LearnPage.NumberCell(v, l));
        }
        return out;
    }

    private static LearnPage.Table table(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        List<String> headers = stringList(n.get("headers"));
        List<List<String>> rows = new ArrayList<>();
        JsonNode rn = n.get("rows");
        if (rn != null && rn.isArray()) {
            for (JsonNode row : rn) {
                if (rows.size() >= MAX_ITEMS) break;
                List<String> cells = stringList(row);
                if (!cells.isEmpty()) rows.add(cells);
            }
        }
        if (headers.isEmpty() && rows.isEmpty()) return null;
        return new LearnPage.Table(headers, List.copyOf(rows));
    }

    private static LearnPage.Side side(JsonNode n, String defaultTone) {
        if (n == null || !n.isObject()) return null;
        String title = clip(text(n, "title"));
        List<String> items = stringList(n.get("items"));
        if (title.isBlank() && items.isEmpty()) return null;
        String tone = text(n, "tone").strip().toLowerCase();
        if (!List.of("good", "bad", "neutral").contains(tone)) tone = defaultTone;
        return new LearnPage.Side(title, tone, items);
    }

    private static List<LearnPage.Node> nodes(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<LearnPage.Node> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (out.size() >= MAX_ITEMS) break;
            if (!item.isObject()) continue;
            String text = clip(text(item, "text"));
            String note = clip(text(item, "note"));
            if (text.isBlank() && note.isBlank()) continue;
            String tone = text(item, "tone").strip().toLowerCase();
            if (!List.of("good", "bad", "neutral", "info").contains(tone)) tone = "neutral";
            out.add(new LearnPage.Node(text, note, tone));
        }
        return out;
    }

    // ── 写：页序列 → md 段 ──

    /** 渲染成可直接拼进卡片正文的 md 段；无页 → 空串（调用方不写这一段）。 */
    public static String renderSection(List<LearnPage> pages) {
        if (pages == null || pages.isEmpty()) return "";
        return "## " + SECTION + "\n\n```" + FENCE_LANG + "\n" + toJson(pages) + "\n```\n\n";
    }

    /** 页序列 → JSON 字符串（省略空字段，便于人读与 diff）。 */
    public static String toJson(List<LearnPage> pages) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (LearnPage p : pages) {
            ObjectNode o = arr.addObject();
            if (!p.kind().isBlank()) o.put("kind", p.kind());
            if (!p.title().isBlank()) o.put("title", p.title());
            if (!p.claim().isBlank()) o.put("claim", p.claim());
            if (!p.bullets().isEmpty()) {
                ArrayNode b = o.putArray("bullets");
                p.bullets().forEach(b::add);
            }
            if (p.table() != null) {
                ObjectNode t = o.putObject("table");
                ArrayNode h = t.putArray("headers");
                p.table().headers().forEach(h::add);
                ArrayNode rows = t.putArray("rows");
                for (List<String> row : p.table().rows()) {
                    ArrayNode r = rows.addArray();
                    row.forEach(r::add);
                }
            }
            if (!p.numbers().isEmpty()) {
                ArrayNode ns = o.putArray("numbers");
                for (LearnPage.NumberCell c : p.numbers()) {
                    ObjectNode c1 = ns.addObject();
                    c1.put("v", c.v());
                    c1.put("l", c.l());
                }
            }
            putSide(o, "left", p.left());
            putSide(o, "right", p.right());
            if (!p.nodes().isEmpty()) {
                ArrayNode ns = o.putArray("nodes");
                for (LearnPage.Node n : p.nodes()) {
                    ObjectNode n1 = ns.addObject();
                    n1.put("text", n.text());
                    if (!n.note().isBlank()) n1.put("note", n.note());
                    if (!"neutral".equals(n.tone())) n1.put("tone", n.tone());
                }
            }
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
        } catch (Exception e) {
            return arr.toString();
        }
    }

    private static void putSide(ObjectNode parent, String field, LearnPage.Side side) {
        if (side == null) return;
        ObjectNode s = parent.putObject(field);
        if (!side.title().isBlank()) s.put("title", side.title());
        if (!side.tone().isBlank()) s.put("tone", side.tone());
        ArrayNode items = s.putArray("items");
        side.items().forEach(items::add);
    }
}
