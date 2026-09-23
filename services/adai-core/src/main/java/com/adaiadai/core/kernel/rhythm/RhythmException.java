package com.adaiadai.core.kernel.rhythm;

/**
 * RhythmException — 节律业务异常（RFC 20260923 B 批）。
 * <p>
 * 违反节律规则时抛出（周期规则非法、条目不存在），由 {@code interfaces.GlobalExceptionHandler}
 * 映射为 400 + 人类可读消息（fail-visible，不静默 no-op）。
 */
public class RhythmException extends RuntimeException {

    public RhythmException(String message) {
        super(message);
    }
}
