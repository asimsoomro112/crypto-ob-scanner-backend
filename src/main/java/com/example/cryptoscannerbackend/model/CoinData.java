package com.example.cryptoscannerbackend.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CoinData {
    private String id;
    private String name;
    private double currentPrice;
    private String volume;
}
