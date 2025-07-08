package com.example.cryptoscannerbackend.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class OrderBlockResult extends CoinData {
    private String orderBlockType;
    private Double orderBlockPrice;
    private String details;
    private String timestamp;
    private String timeframe;
    private Double obCandleOpen;
    private Double obCandleHigh;
    private Double obCandleLow;
    private Double obCandleClose;
    private Double obZoneStart; // Added for clarity on frontend
    private Double obZoneEnd;   // Added for clarity on frontend
}
