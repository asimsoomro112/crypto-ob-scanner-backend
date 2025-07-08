package com.example.cryptoscannerbackend.service;

import com.example.cryptoscannerbackend.model.CoinData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor; // Keep for other classes if needed, but not for Candlestick
import lombok.Data;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class BinanceApiClient {

    @Value("${binance.futures.base.url}")
    private String futuresBaseUrl;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<CoinData> getTopVolumeFuturesCoins(int limit) throws IOException {
        String url = String.format("%s/fapi/v1/ticker/24hr", futuresBaseUrl);
        System.out.println("Fetching top volume coins from URL: " + url);
        Request request = new Request.Builder().url(url).build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No error body.";
                System.err.println("Failed to fetch 24hr ticker: " + response.code() + " " + response.message() + " | Body: " + errorBody);
                throw new IOException("Failed to fetch 24hr ticker: " + response.code() + " " + response.message());
            }
            String responseBody = response.body().string();
            System.out.println("Raw 24hr Ticker Response (first 500 chars): " + responseBody.substring(0, Math.min(responseBody.length(), 500)) + "...");

            JsonNode rootNode = objectMapper.readTree(responseBody);
            List<CoinData> highVolumeCoins = new ArrayList<>();
            int processedCount = 0;
            for (JsonNode node : rootNode) {
                try {
                    String symbol = node.get("symbol").asText();
                    double volume = node.get("quoteVolume").asDouble();
                    double currentPrice = node.get("lastPrice").asDouble();

                    boolean isUsdtPair = symbol.endsWith("USDT");
                    boolean isPerpetualContract = node.has("contractType") && "PERPETUAL".equals(node.get("contractType").asText());
                    boolean isContractTypeMissing = !node.has("contractType"); // Some older symbols might not have this field

                    if (isUsdtPair && (isPerpetualContract || isContractTypeMissing)) {
                        CoinData coin = new CoinData();
                        coin.setId(symbol);
                        coin.setName(symbol.replace("USDT", ""));
                        coin.setCurrentPrice(currentPrice);
                        coin.setVolume(formatVolume(volume));
                        highVolumeCoins.add(coin);
                        processedCount++;
                    } else {
                        // System.out.println("  DEBUG: Skipping " + symbol + " - isUsdtPair: " + isUsdtPair + ", isPerpetualContract: " + isPerpetualContract + ", isContractTypeMissing: " + isContractTypeMissing);
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing ticker data for a symbol: " + node.toString() + " | Error:  " + e.getMessage());
                }
            }
            System.out.println("Finished processing ticker data. Found " + processedCount + " USDT Perpetual Futures symbols before sorting/limiting.");

            return highVolumeCoins.stream()
                    // Corrected: Explicitly cast 'c' to CoinData
                    .sorted(Comparator.comparingDouble(c -> parseVolumeString(((CoinData) c).getVolume())).reversed())
                    .limit(limit)
                    .collect(Collectors.toList());

        } catch (okhttp3.internal.http2.StreamResetException e) {
            System.err.println("StreamResetException: This often indicates a network issue or server side problem. " + e.getMessage());
            throw new IOException("Network connection issue with Binance API.", e);
        } catch (IOException e) {
            System.err.println("IOException during API call to Binance: " + e.getMessage());
            throw e;
        }
    }

    public List<Candlestick> getCandlestickData(String symbol, String interval, int limit) throws IOException {
        String url = String.format("%s/fapi/v1/klines?symbol=%s&interval=%s&limit=%d", futuresBaseUrl, symbol, interval, limit);
        System.out.println("  Fetching klines for " + symbol + " (" + interval + ") from URL: " + url);

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No error body.";
                System.err.println("Failed to fetch klines for " + symbol + ": " + response.code() + " " + response.message() + " | Body: " + errorBody);
                throw new IOException("Failed to fetch klines for " + symbol + ": " + response.code() + " " + response.message());
            }
            String responseBody = response.body().string();
            JsonNode rootNode = objectMapper.readTree(responseBody);
            List<Candlestick> candlesticks = new ArrayList<>();
            for (JsonNode node : rootNode) {
                candlesticks.add(new Candlestick(
                        node.get(0).asLong(),
                        node.get(1).asDouble(),
                        node.get(2).asDouble(),
                        node.get(3).asDouble(),
                        node.get(4).asDouble(),
                        node.get(5).asDouble()
                ));
            }
            return candlesticks;
        }
    }

    private static String formatVolume(double volume) {
        if (volume >= 1_000_000_000) {
            return String.format("%.1fB", volume / 1_000_000_000);
        } else if (volume >= 1_000_000) {
            return String.format("%.1fM", volume / 1_000_000);
        } else if (volume >= 1_000) {
            return String.format("%.1fK", volume / 1_000);
        }
        return String.format("%.0f", volume);
    }

    private static double parseVolumeString(String volumeStr) {
        volumeStr = volumeStr.toUpperCase();
        if (volumeStr.endsWith("B")) {
            return Double.parseDouble(volumeStr.replace("B", "")) * 1_000_000_000;
        } else if (volumeStr.endsWith("M")) {
            return Double.parseDouble(volumeStr.replace("M", "")) * 1_000_000;
        } else if (volumeStr.endsWith("K")) {
            return Double.parseDouble(volumeStr.replace("K", "")) * 1_000;
        }
        return Double.parseDouble(volumeStr);
    }

    @Data
    // Removed @AllArgsConstructor here to avoid duplicate constructor error
    public static class Candlestick {
        private long openTime;
        private double open;
        private double high;
        private double low;
        private double close;
        private double volume;

        public Candlestick(long openTime, double open, double high, double low, double close, double volume) {
            this.openTime = openTime;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
    }
}
