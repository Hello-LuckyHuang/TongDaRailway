package com.hxzhitang.tongdarailway.util;

import net.neoforged.neoforge.common.Tags;

import java.util.*;

/**
 * RandomPool<E>
 *
 * 规则：
 * 1) add(obj, id, tags...) 添加带标签对象（id 唯一，重复 add 会覆盖）
 * 2) get(seed, tag1, tag2...)
 *    (1) 候选 = 所有带 tag1 的对象
 *    (2) 在候选中，选出“命中 tag2... 数量最多”的那一批对象（并列都算）
 *    (3) 用 seed 从该批对象中随机返回一个
 *    (4) 若候选中没有任何对象命中任意 tag2，则从(候选 且 带 "default")中随机返回一个
 *        若该 fallback 也为空，则返回 null
 *    (5) 若没有任何对象带 tag1，返回 null
 * 3) getById(id) 直接取
 */
public class RandomPool<E> {

    private static final String DEFAULT_TAG = "default";

    private static final class Entry<E> {
        final long id;
        final E obj;
        final Set<String> tags; // 已归一化（trim / 非空 / 去重）

        Entry(long id, E obj, Set<String> tags) {
            this.id = id;
            this.obj = obj;
            this.tags = tags;
        }
    }

    // id -> entry
    private final Map<Long, Entry<E>> byId = new HashMap<>();

    // tag -> ids (用 LinkedHashSet 保持稳定迭代顺序，便于可重复性)
    private final Map<String, LinkedHashSet<Long>> idsByTag = new HashMap<>();

    /** 添加/覆盖对象 */
    public void add(E obj, long id, String... tags) {
        if (obj == null) throw new IllegalArgumentException("obj cannot be null");

        Set<String> normTags = normalizeTags(tags);

        // 如果覆盖旧的，需要先从索引里移除旧标签关联
        Entry<E> old = byId.get(id);
        if (old != null) {
            for (String t : old.tags) {
                LinkedHashSet<Long> set = idsByTag.get(t);
                if (set != null) {
                    set.remove(id);
                    if (set.isEmpty()) idsByTag.remove(t);
                }
            }
        }

        Entry<E> e = new Entry<>(id, obj, normTags);
        byId.put(id, e);

        // 建立新索引
        for (String t : normTags) {
            idsByTag.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(id);
        }
    }

    /** 直接按 id 取对象；不存在返回 null */
    public E getById(long id) {
        Entry<E> e = byId.get(id);
        return e == null ? null : e.obj;
    }

    /**
     * 按规则随机获取
     * @param seed 随机种子（相同输入应得到稳定结果）
     * @param tag1 第一限定标签（必填；为空则直接返回 null）
     * @param tag2s 次级标签集合（可空/可不传）
     */
    public E get(long seed, String tag1, String... tag2s) {
        String t1 = normalizeTag(tag1);
        if (t1 == null) return null;

        LinkedHashSet<Long> baseIdsSet = idsByTag.get(t1);
        if (baseIdsSet == null || baseIdsSet.isEmpty()) return null;

        // base 候选列表（稳定顺序）
        List<Entry<E>> base = new ArrayList<>(baseIdsSet.size());
        for (Long id : baseIdsSet) {
            Entry<E> e = byId.get(id);
            if (e != null) base.add(e);
        }
        if (base.isEmpty()) return null;

        // 归一化 tag2
        Set<String> t2set = normalizeTags(tag2s);
        // 如果 tag2 为空：按“最多命中”其实全是 0；会走 fallback 规则 (4)，即选 default。
        // 这跟你的规则更一致：tag2.. 为空 => 没有人“带有tag2中的标签”，因此 fallback。
        // 如果你希望 tag2 为空时直接在 tag1 范围内随机，告诉我我给你改一行。
        int maxHit = 0;
        boolean anyHit = false;

        // 先扫描求 maxHit
        if (!t2set.isEmpty()) {
            for (Entry<E> e : base) {
                int hit = countTagHits(e.tags, t2set);
                if (hit > 0) anyHit = true;
                if (hit > maxHit) maxHit = hit;
            }
        }

        List<Entry<E>> candidates;

        if (anyHit) {
            // (2) 取命中数量 == maxHit 的那批
            candidates = new ArrayList<>();
            for (Entry<E> e : base) {
                if (countTagHits(e.tags, t2set) == maxHit) {
                    candidates.add(e);
                }
            }
        } else {
            // (4) 没有任何对象命中任意 tag2 => 从 base 且 带 default 的里面取
            candidates = new ArrayList<>();
            for (Entry<E> e : base) {
                if (e.tags.contains(DEFAULT_TAG)) candidates.add(e);
            }
        }

        if (candidates.isEmpty()) return null;

        int idx = pickIndex(seed, candidates.size());
        return candidates.get(idx).obj;
    }

    // ----------------- helpers -----------------

    private static int countTagHits(Set<String> entryTags, Set<String> desired) {
        int c = 0;
        for (String t : desired) {
            if (entryTags.contains(t)) c++;
        }
        return c;
    }

    /**
     * 用 seed 生成 [0, bound) 的稳定下标
     * 使用 SplittableRandom（JDK8+），同 seed 同 bound 结果稳定
     */
    private static int pickIndex(long seed, int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be > 0");
        return new SplittableRandom(seed).nextInt(bound);
    }

    private static String normalizeTag(String tag) {
        if (tag == null) return null;
        String t = tag.trim();
        return t.isEmpty() ? null : t;
    }

    private static Set<String> normalizeTags(String... tags) {
        if (tags == null || tags.length == 0) return Collections.emptySet();
        // LinkedHashSet 保持稳定顺序
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String tag : tags) {
            String t = normalizeTag(tag);
            if (t != null) set.add(t);
        }
        return set.isEmpty() ? Collections.emptySet() : set;
    }

    public static void main111(String[] args) {
        System.out.println(Tags.Biomes.IS_OCEAN.location());

        // 创建RandomPool实例
        RandomPool<String> pool = new RandomPool<>();

        // 添加对象
        pool.add("Apple", 1, "fruit", "red", "default");
        pool.add("Cherry", 6, "fruit", "red", "juicy");
        pool.add("Orange", 5, "fruit", "juicy");
        pool.add("Banana", 2, "fruit", "yellow");
        pool.add("Carrot", 3, "vegetable", "orange", "default");
        pool.add("Spinach", 4, "vegetable", "green");

        // 获取ID为1的对象
        System.out.println(pool.getById(1)); // 输出 "Apple"

        // 获取带有"fruit"标签并且带有"red"标签的对象
        System.out.println(pool.get(System.currentTimeMillis(), "fruit", "red", "juicy")); // 输出 "Apple"

        // 获取带有"fruit"标签并且带有"default"标签的对象
        System.out.println(pool.get(12345, "fruit")); // 输出 "Apple" 或 "Banana"（随机）

        // 获取没有"fruit"标签的情况下，带有"default"标签的对象
        System.out.println(pool.get(12345, "vegetable", "yellow")); // 输出 "Carrot" 或 "Spinach"（随机）
    }
}