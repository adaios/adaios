package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.identity.IdentityProfile;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * IdentityController — 个人档案读写入口。
 * <p>
 * GET  /api/v1/identity → 读取
 * PUT  /api/v1/identity → 全量覆盖
 */
@RestController
@RequestMapping("/api/v1/identity")
public class IdentityController {

    private static final Logger log = LoggerFactory.getLogger(IdentityController.class);

    private final IdentityRepository identityRepository;

    public IdentityController(IdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    /**
     * 读取个人档案。文件不存在时返回默认档案，不会 404。
     */
    @GetMapping
    public ResponseEntity<IdentityProfile> getIdentity(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        return ResponseEntity.ok(identityRepository.load(userId).orElse(null));
    }

    /**
     * 更新（全量覆盖）个人档案。
     * <p>
     * 2026-09-16「第一次见面」批：name / tags **不再强制非空**——新用户还没有昵称、没有标签，
     * 不该因为「档案本来就是空的」而保存失败（此前 400「name 不能为空」把零画像用户挡在门外，
     * 档案页永远停在默认值）。空 name 语义 = 还没告知称呼，ContextEngine 注入时跳过该行。
     */
    @PutMapping
    public ResponseEntity<?> updateIdentity(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody IdentityRequest request) {

        IdentityProfile profile = new IdentityProfile(
                request.name() != null ? request.name().trim() : "",
                request.preferences() != null ? request.preferences() : Map.of(),
                request.rules() != null ? request.rules() : Map.of(),
                request.tags() != null ? request.tags() : List.of()
        );

        try {
            IdentityProfile saved = identityRepository.save(userId, profile);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("身份保存失败", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "身份保存失败: " + e.getMessage()));
        }
    }

    // ── Request DTO ──

    /**
     * 2026-09-16：name / tags 均允许为空（新用户零画像可保存）。
     */
    public record IdentityRequest(
            String name,
            Map<String, String> preferences,
            Map<String, String> rules,
            List<String> tags
    ) {}
}
