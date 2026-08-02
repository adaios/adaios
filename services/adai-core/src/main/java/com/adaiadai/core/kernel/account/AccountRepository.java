package com.adaiadai.core.kernel.account;

import java.util.List;
import java.util.Optional;

/**
 * AccountRepository — 账号存储接口（系统级，File First 于 {@code data/accounts/}）。
 * <p>
 * 账号不属于任何用户的 {@code data/{userId}/} 数据层，是全系统共享的账号表，
 * 因此方法不传 userId（与多用户数据 Repository 区分）。
 */
public interface AccountRepository {

    List<Account> findAll();

    Optional<Account> findById(String userId);

    /** 新增或更新（upsert）。 */
    Account save(Account account);

    boolean delete(String userId);
}
