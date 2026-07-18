package com.adaiadai.core.kernel.identity;

import java.util.Optional;

/**
 * IdentityRepository — Identity 的存储接口（端口定义）。
 * <p>
 * 采用 File First：从 {@code data/identity/profile.md} 读取。
 * 实现由 {@code infrastructure.storage} 提供。
 */
public interface IdentityRepository {

    /**
     * 加载个人档案。
     *
     * @return 个人档案
     */
    Optional<IdentityProfile> load();
}
