package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.identity.IdentityProfile;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IdentityController 测试。
 * 验证读取、更新、默认降级行为。
 */
class IdentityControllerTest {

    private final IdentityRepository repo = new IdentityRepository() {
        @Override
        public Optional<IdentityProfile> load() {
            return Optional.of(new IdentityProfile("阿呆", Map.of(), Map.of(), List.of("投资")));
        }

        @Override
        public IdentityProfile save(IdentityProfile profile) {
            return profile;
        }
    };

    private final IdentityRepository emptyRepo = new IdentityRepository() {
        @Override
        public Optional<IdentityProfile> load() {
            return Optional.empty();
        }

        @Override
        public IdentityProfile save(IdentityProfile profile) {
            return profile;
        }
    };

    @Test
    void getIdentity_returns200() {
        var controller = new IdentityController(repo);
        ResponseEntity<IdentityProfile> resp = controller.getIdentity();
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("阿呆", resp.getBody().name());
    }

    @Test
    void getIdentity_whenMissing_returns200WithNullBody() {
        // 文件不存在时返回 200 + 空响应体（前端自行处理）
        var controller = new IdentityController(emptyRepo);
        ResponseEntity<IdentityProfile> resp = controller.getIdentity();
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void updateIdentity() {
        var controller = new IdentityController(repo);
        var request = new IdentityController.IdentityRequest("新名字", Map.of(), Map.of(), List.of("A", "B"));
        ResponseEntity<?> resp = controller.updateIdentity(request);
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void updateIdentity_missingName_returns400() {
        var controller = new IdentityController(repo);
        var request = new IdentityController.IdentityRequest("", Map.of(), Map.of(), List.of("A"));
        ResponseEntity<?> resp = controller.updateIdentity(request);
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void updateIdentity_missingTags_returns400() {
        var controller = new IdentityController(repo);
        var request = new IdentityController.IdentityRequest("名字", Map.of(), Map.of(), List.of());
        ResponseEntity<?> resp = controller.updateIdentity(request);
        assertEquals(400, resp.getStatusCode().value());
    }
}
