package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.domain.trading.PortfolioSnapshot;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.TradeDirection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * TradingController — 交易相关的 REST API。
 */
@RestController
@RequestMapping("/api/v1/trading")
public class TradingController {

    private final TradingAppService tradingAppService;

    public TradingController(TradingAppService tradingAppService) {
        this.tradingAppService = tradingAppService;
    }

    /**
     * 查询当前持仓。
     */
    @GetMapping("/positions")
    public ResponseEntity<List<Position>> getPositions() {
        return ResponseEntity.ok(tradingAppService.getPositions());
    }

    /**
     * 查询投资组合快照。
     */
    @GetMapping("/portfolio")
    public ResponseEntity<PortfolioSnapshot> getPortfolio() {
        return ResponseEntity.ok(tradingAppService.getPortfolioSnapshot());
    }

    /**
     * 记录一笔交易（买入/卖出）。
     */
    @PostMapping("/trades")
    public ResponseEntity<List<Position>> recordTrade(@Valid @RequestBody TradeRequest request) {
        List<Position> updated = tradingAppService.recordTrade(
                request.symbol(), request.name(),
                request.direction(), request.price(), request.volume()
        );
        return ResponseEntity.ok(updated);
    }

    // ── DTO ──

    public record TradeRequest(
            @NotBlank String symbol,
            @NotBlank String name,
            TradeDirection direction,
            @Positive BigDecimal price,
            @Positive int volume
    ) {}
}
