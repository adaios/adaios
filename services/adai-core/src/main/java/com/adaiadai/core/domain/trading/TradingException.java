package com.adaiadai.core.domain.trading;

/**
 * 交易领域业务异常。
 * <p>
 * 交易操作违反业务规则时抛出（如卖出未持有标的、卖出数量超过持仓），
 * 由 {@code interfaces.GlobalExceptionHandler} 映射为 400 + 人类可读消息，
 * 避免静默 no-op 造成数据失真（REVIEW #147）。
 */
public class TradingException extends RuntimeException {

    public TradingException(String message) {
        super(message);
    }
}
