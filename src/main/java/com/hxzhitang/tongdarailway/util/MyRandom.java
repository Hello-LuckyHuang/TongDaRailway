package com.hxzhitang.tongdarailway.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class MyRandom {

    /**
     * 根据种子从Map中随机选择一个值
     * @param map 输入的Map集合
     * @param seed 随机种子
     * @param <K> 键的类型
     * @param <V> 值的类型
     * @return 随机选择的值，如果Map为空则返回null
     */
    public static <K, V> V getRandomValueFromMap(Map<K, V> map, long seed) {
        if (map == null || map.isEmpty()) {
            return null;
        }

        Random random = new Random(seed);
        List<V> values = new ArrayList<>(map.values());
        int randomIndex = random.nextInt(values.size());

        return values.get(randomIndex);
    }
}
