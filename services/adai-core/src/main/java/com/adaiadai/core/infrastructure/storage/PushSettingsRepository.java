package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.PushSettings;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PushSettingsRepository — 推送开关持久化（RFC 20260817 交易推送体验）。
 * <p>
 * 文件 {@code data/{userId}/trading/push-settings.json}，JSON 对象 {类型: 开关}。
 * 缺失/损坏 → 默认全开（不阻断推送）。写为原子（tmp+move）。
 */
@Repository
public class PushSettingsRepository {

    private static final Logger log = LoggerFactory.getLogger(PushSettingsRepository.class);
    private static final String SETTINGS_PATH = "trading/push-settings.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileStorage fileStorage;

    public PushSettingsRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /** 读取用户推送开关；无文件/损坏 → 默认全开。 */
    public PushSettings findByUser(String userId) {
        String content = fileStorage.read(userId, SETTINGS_PATH);
        if (content == null || content.isBlank()) return PushSettings.defaults();
        try {
            Map<String, Boolean> m = new LinkedHashMap<>();
            var node = MAPPER.readTree(content);
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isBoolean()) m.put(e.getKey(), e.getValue().asBoolean());
            });
            return new PushSettings(m);
        } catch (Exception e) {
            log.warn("读取推送开关失败（默认全开）| userId={} | {}", userId, e.getMessage());
            return PushSettings.defaults();
        }
    }

    /** 保存用户推送开关。 */
    public void save(String userId, PushSettings settings) {
        try {
            String json = MAPPER.writeValueAsString(settings.enabled());
            fileStorage.write(userId, SETTINGS_PATH, json);
        } catch (Exception e) {
            log.warn("保存推送开关失败 | userId={} | {}", userId, e.getMessage());
        }
    }
}
