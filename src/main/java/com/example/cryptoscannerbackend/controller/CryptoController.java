package com.example.cryptoscannerbackend.controller;

import com.example.cryptoscannerbackend.model.CoinData;
import com.example.cryptoscannerbackend.model.OrderBlockResult;
import com.example.cryptoscannerbackend.service.BinanceApiClient;
import com.example.cryptoscannerbackend.service.OrderBlockDetector;
import com.example.cryptoscannerbackend.service.UserService;
import com.example.cryptoscannerbackend.security.services.UserDetailsImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service; // This should be @RestController
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RestController // Correct annotation for a REST controller
@RequestMapping("/api")
@CrossOrigin(origins = "*", maxAge = 3600) // Temporarily allow all origins for local testing, change to "https://ccscanner.netlify.app" for production
public class CryptoController {

    @Autowired
    private BinanceApiClient binanceApiClient;
    @Autowired
    private OrderBlockDetector orderBlockDetector;
    @Autowired
    private UserService userService;

    private final ConcurrentMap<String, OrderBlockResult> latestScanResults = new ConcurrentHashMap<>();

    @GetMapping("/scan-order-blocks")
    public List<OrderBlockResult> getOrderBlockScanResults(
            @RequestParam(defaultValue = "4h") String interval,
            @RequestParam(defaultValue = "0.15") double minBodyRatio,
            @RequestParam(defaultValue = "0.0002") double minPriceChange,
            @RequestParam(defaultValue = "0.5") double volumeFactor,
            @RequestParam(defaultValue = "true") boolean requireBOS,
            @RequestParam(defaultValue = "true") boolean requireC3ClosePastC2,
            @RequestParam(defaultValue = "true") boolean requireFVG,
            @RequestParam(defaultValue = "true") boolean requireUnmitigated,
            @RequestParam(defaultValue = "0.0") double minFvgDepthRatio,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal().equals("anonymousUser")) {
            System.out.println("Access Denied: User not authenticated.");
            return Collections.emptyList();
        }

        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        String username = userDetails.getUsername();

        Map<String, Object> userStatus = userService.getUserStatus(username);
        boolean isPremium = (boolean) userStatus.getOrDefault("isPremium", false);
        boolean trialActive = (boolean) userStatus.getOrDefault("trialActive", false);

        if (!isPremium && !trialActive) {
            System.out.println("Access Denied: User " + username + " is neither premium nor has an active trial.");
            return Collections.emptyList();
        }

        int coinLimit = isPremium ? 100 : 20; // Premium users get 100 coins, trial users get 20
        String effectiveInterval = isPremium ? interval : "4h"; // Trial users restricted to 4h

        if (!isPremium && !interval.equals("4h")) {
            System.out.println("Trial user requested " + interval + ", but only 4h is allowed. Using 4h.");
        }

        System.out.println("API endpoint hit: Performing one-time scan for order blocks on Binance Futures for interval: " + effectiveInterval + " for user: " + username + " (Premium: " + isPremium + ", Trial: " + trialActive + ")");
        System.out.println(String.format("  Parameters: minBodyRatio=%.4f, minPriceChange=%.4f, volumeFactor=%.2f, requireBOS=%b, requireC3ClosePastC2=%b, requireFVG=%b, requireUnmitigated=%b, minFvgDepthRatio=%.4f", minBodyRatio, minPriceChange, volumeFactor, requireBOS, requireC3ClosePastC2, requireFVG, requireUnmitigated, minFvgDepthRatio));

        List<OrderBlockResult> results = new ArrayList<>();
        try {
            List<CoinData> highVolumeCoins = binanceApiClient.getTopVolumeFuturesCoins(coinLimit);
            System.out.println("Found " + highVolumeCoins.size() + " top volume futures coins.");

            for (CoinData coin : highVolumeCoins) {
                List<BinanceApiClient.Candlestick> klines = binanceApiClient.getCandlestickData(coin.getId(), effectiveInterval, 200);
                if (klines != null && klines.size() >= OrderBlockDetector.WINDOW_SIZE) {
                    OrderBlockResult result = orderBlockDetector.detectOrderBlock(
                            coin, klines, effectiveInterval, minBodyRatio, minPriceChange, volumeFactor, requireBOS, requireC3ClosePastC2, requireFVG, requireUnmitigated, minFvgDepthRatio
                    );
                    results.add(result);
                    latestScanResults.put(coin.getId(), result);
                    System.out.println("Scanned " + coin.getName() + " (" + coin.getId() + "): " + result.getOrderBlockType() + " at " + (result.getOrderBlockPrice() != null ? String.format("$%.2f", result.getOrderBlockPrice()) : "N/A"));
                } else {
                    System.out.println("Not enough klines data for " + coin.getId() + " (found " + (klines != null ? klines.size() : 0) + "), skipping order block detection.");
                    OrderBlockResult noDataResult = new OrderBlockResult();
                    noDataResult.setId(coin.getId());
                    noDataResult.setName(coin.getName());
                    noDataResult.setCurrentPrice(coin.getCurrentPrice());
                    noDataResult.setVolume(coin.getVolume());
                    noDataResult.setTimestamp(LocalDateTime.now().atZone(ZoneId.systemDefault()).toLocalTime().toString());
                    noDataResult.setOrderBlockType("None");
                    noDataResult.setDetails("Insufficient candlestick data for analysis in scheduled scan.");
                    noDataResult.setTimeframe(effectiveInterval);
                    latestScanResults.put(coin.getId(), noDataResult);
                }
            }
        } catch (IOException e) {
            System.err.println("Error during one-time scan: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
        return results;
    }

    // This method is called by the ScanScheduler for background scans
    public void performScheduledScan() {
        System.out.println("Performing scheduled background scan for order blocks on Binance Futures...");
        try {
            List<CoinData> highVolumeCoins = binanceApiClient.getTopVolumeFuturesCoins(100); // Scan all 100 for scheduled
            System.out.println("Found " + highVolumeCoins.size() + " top volume futures coins for scheduled scan.");

            // Default parameters for scheduled scan (can be different from frontend defaults)
            double defaultMinBodyRatio = 0.15;
            double defaultMinPriceChange = 0.0005;
            double defaultVolumeFactor = 0.6;
            String defaultTimeframe = "4h";
            boolean defaultRequireBOS = true;
            boolean defaultRequireC3ClosePastC2 = true;
            boolean defaultRequireFVG = true;
            boolean defaultRequireUnmitigated = true;
            double defaultMinFvgDepthRatio = 0.05;

            for (CoinData coin : highVolumeCoins) {
                List<BinanceApiClient.Candlestick> klines = binanceApiClient.getCandlestickData(coin.getId(), defaultTimeframe, 200);
                if (klines != null && klines.size() >= OrderBlockDetector.WINDOW_SIZE) {
                    OrderBlockResult result = orderBlockDetector.detectOrderBlock(
                            coin, klines, defaultTimeframe, defaultMinBodyRatio, defaultMinPriceChange, defaultVolumeFactor, defaultRequireBOS, defaultRequireC3ClosePastC2, defaultRequireFVG, defaultRequireUnmitigated, defaultMinFvgDepthRatio
                    );
                    latestScanResults.put(coin.getId(), result);
                    System.out.println("  Scheduled Scanned " + coin.getName() + " (" + coin.getId() + "): " + result.getOrderBlockType());
                } else {
                    System.out.println("  Scheduled scan: Not enough klines data for " + coin.getId() + " (found " + (klines != null ? klines.size() : 0) + "), skipping order block detection.");
                    OrderBlockResult noDataResult = new OrderBlockResult();
                    noDataResult.setId(coin.getId());
                    noDataResult.setName(coin.getName());
                    noDataResult.setCurrentPrice(coin.getCurrentPrice());
                    noDataResult.setVolume(coin.getVolume());
                    noDataResult.setTimestamp(LocalDateTime.now().atZone(ZoneId.systemDefault()).toLocalTime().toString());
                    noDataResult.setOrderBlockType("None");
                    noDataResult.setDetails("Insufficient candlestick data for analysis in scheduled scan.");
                    noDataResult.setTimeframe(defaultTimeframe);
                    latestScanResults.put(coin.getId(), noDataResult);
                }
            }
        } catch (IOException e) {
            System.err.println("Error during scheduled scan: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
