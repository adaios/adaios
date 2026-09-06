package com.adaiadai.core.domain.learn;

/**
 * learn 领域业务异常（RFC 20260829）。
 * <p>
 * 喂入/卡片化违反 learn 业务规则时抛出，由 {@code interfaces.GlobalExceptionHandler}
 * 映射为 400 + 人类可读消息（fail-visible，不静默 no-op）。
 */
public class LearnException extends RuntimeException {

    public LearnException(String message) {
        super(message);
    }
}
