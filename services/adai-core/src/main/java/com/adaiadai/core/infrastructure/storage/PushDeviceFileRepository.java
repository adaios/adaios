package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.push.PushDevice;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * PushDeviceFileRepository — 推送设备登记存储（RFC 20260913 APNs 批）。
 * <p>
 * 文件 {@code data/{userId}/push/devices.json}（File First，与 data/ 下其它个人资产同构）：
 * <pre>
 * [{"token":"a1b2...","platform":"ios","environment":"sandbox",
 *   "bundleId":"com.adaiadai.adaiApp","label":"iPhone",
 *   "registeredAt":"2026-09-13T05:10:00Z","lastSeenAt":"2026-09-13T05:10:00Z"}]
 * </pre>
 * 写走 {@link FileStorage#write}（临时文件 + ATOMIC_MOVE，见 LocalFileStorage），
 * 读改写套 per-user 条带锁（同 MarketPushRepository 先例，防并发 RMW 丢设备）。
 * <p>
 * <b>损坏文件的处理分读写两侧</b>（fail-visible 取舍，2026-09-13 本批）：
 * <ul>
 *   <li>读路径（推送投递）：损坏 → 返回空并 warn。推送是尽力而为的动作，
 *       不该因为一个坏文件把整轮推送打挂。</li>
 *   <li>写路径（注册/注销）：损坏 → 抛 {@link StorageException} 拒绝写回。
 *       若像 PushSettingsRepository 那样「损坏当空」再写，等于用一个空列表覆盖掉
 *       文件里可能还完整的其它设备——静默数据丢失（P0 类）。宁可让用户看到失败。</li>
 * </ul>
 */
@Repository
public class PushDeviceFileRepository {

    private static final Logger log = LoggerFactory.getLogger(PushDeviceFileRepository.class);
    private static final String DEVICES_PATH = "push/devices.json";

    /**
     * FAIL_ON_TRAILING_TOKENS：Jackson 默认**忽略尾部多余内容**——截断/写坏的文件
     * （如 {@code [ ... ] ]}）会被当成合法 JSON 读出前半段。对「损坏必须被发现」的写路径来说，
     * 这种宽容正是静默丢数据的入口，故显式打开严格模式（本类自写的文件一定是干净 JSON）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final TypeReference<List<PushDevice>> DEVICE_LIST = new TypeReference<>() {};

    // 同 MarketPushRepository：固定 16 条带锁，不按 userId 建 map（防锁池无限增长）
    private static final int LOCK_STRIPES = 16;
    private final Object[] locks = new Object[LOCK_STRIPES];

    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    private final FileStorage fileStorage;

    public PushDeviceFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    private Object lockFor(String userId) {
        int h = (userId != null ? userId : "default").hashCode();
        return locks[(h ^ (h >>> 16)) & (LOCK_STRIPES - 1)];
    }

    /**
     * 读取用户的推送设备；无文件 → 空列表，损坏 → 空列表 + warn（读路径降级）。
     * <p>
     * 用于推送投递：拿到空列表就静默跳过，不阻断推送生产方。
     */
    public List<PushDevice> findByUser(String userId) {
        String content = fileStorage.read(userId, DEVICES_PATH);
        if (content == null || content.isBlank()) return List.of();
        try {
            List<PushDevice> devices = MAPPER.readValue(content, DEVICE_LIST);
            return devices != null ? devices : List.of();
        } catch (Exception e) {
            log.warn("读取推送设备失败（本轮推送跳过该用户）| userId={} | {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 登记（或刷新）一台设备：同 token 幂等 upsert。
     *
     * @return 登记后的设备
     * @throws StorageException 存量文件损坏（拒绝覆盖，防静默丢设备）
     */
    public PushDevice save(String userId, PushDevice device) {
        synchronized (lockFor(userId)) {
            List<PushDevice> devices = new ArrayList<>(readOrThrow(userId));
            boolean replaced = false;
            for (int i = 0; i < devices.size(); i++) {
                PushDevice old = devices.get(i);
                if (old.token().equalsIgnoreCase(device.token())) {
                    // 同 token 重复上报：保留首次注册时间与原 token 写法，刷新环境/备注/lastSeenAt
                    devices.set(i, new PushDevice(
                            old.token(),
                            device.platform(),
                            device.environment(),
                            device.bundleId(),
                            device.label() != null && !device.label().isBlank() ? device.label() : old.label(),
                            old.registeredAt(),
                            device.lastSeenAt()));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) devices.add(device);
            write(userId, devices);
            log.info("推送设备登记 | userId={} | platform={} | env={} | 现有设备数={} | {}",
                    userId, device.platform(), device.environment(), devices.size(),
                    replaced ? "刷新" : "新增");
            return device;
        }
    }

    /**
     * 注销设备（用户登出 / APNs 回 410 Unregistered）。
     *
     * @return 是否真的删掉了（幂等：不存在返回 false）
     */
    public boolean remove(String userId, String token) {
        synchronized (lockFor(userId)) {
            List<PushDevice> devices = new ArrayList<>(readOrThrow(userId));
            int before = devices.size();
            devices.removeIf(d -> d.token().equalsIgnoreCase(token));
            if (devices.size() == before) return false;
            write(userId, devices);
            log.info("推送设备注销 | userId={} | 剩余设备数={}", userId, devices.size());
            return true;
        }
    }

    /** 读：文件损坏抛异常（写路径专用，防「损坏当空」后覆盖丢数据）。 */
    private List<PushDevice> readOrThrow(String userId) {
        String content = fileStorage.read(userId, DEVICES_PATH);
        if (content == null || content.isBlank()) return List.of();
        try {
            List<PushDevice> devices = MAPPER.readValue(content, DEVICE_LIST);
            return devices != null ? devices : List.of();
        } catch (Exception e) {
            throw new StorageException("推送设备文件已损坏，本次写入已取消（避免覆盖其它设备）: " + e.getMessage(), e);
        }
    }

    private void write(String userId, List<PushDevice> devices) {
        try {
            fileStorage.write(userId, DEVICES_PATH, MAPPER.writeValueAsString(devices));
        } catch (Exception e) {
            throw new StorageException("推送设备写入失败: " + e.getMessage(), e);
        }
    }
}
