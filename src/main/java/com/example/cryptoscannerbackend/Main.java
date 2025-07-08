package com.example.cryptoscannerbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import lombok.Data; // Ensure Lombok is correctly set up in your IDE

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.lang.Math.abs; // For Math.abs in isImpulsive

// Main Spring Boot Application class
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableScheduling // Enable Spring's scheduled task execution
public class Main {

    public static void main(String[] args) {
        // FIX: Specify the primary source class for SpringApplication.run()
        SpringApplication.run(Main.class, args);
    }

    // --- Data Models (as static nested classes) ---

    @Data
    public static class CoinData {
        private String id; // Symbol, e.g., "BTCUSDT"
        private String name; // e.g., "Bitcoin" (derived from symbol)
        private double currentPrice;
        private String volume; // Formatted volume string, e.g., "50B"

        public String getVolume() {
            return volume;
        }
    }

    @Data
    public static class OrderBlockResult extends CoinData {
        private String orderBlockType; // e.g., "Buying (Bullish)", "Selling (Bearish)", "None"
        private Double orderBlockPrice; // The price level of the detected order block, can be null
        private String details; // A descriptive message about the detection
        private String timestamp; // When the scan was performed (e.g., "00:05:56.129")
        private String timeframe; // The timeframe used for detection (e.g., "4h")
    }

    // --- Binance API Client Service (as a static nested class) ---
    @Service
    public static class BinanceApiClient {

        @Value("${binance.api.key}")
        private String apiKey;
        @Value("${binance.api.secret}")
        private String apiSecret;

        @Value("${binance.futures.base.url}")
        private String futuresBaseUrl;

        private final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        private final ObjectMapper objectMapper = new ObjectMapper();

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

        public List<CoinData> getTopVolumeFuturesCoins(int limit) throws IOException {
            String url = String.format("%s/fapi/v1/ticker/24hr", futuresBaseUrl);
            System.out.println("Fetching top volume coins from URL: " + url);

            Request request = new Request.Builder()
                    .url(url)
                    .build();

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

                        // Relaxed filtering for USDT Perpetual Futures:
                        // 1. Must end with "USDT".
                        // 2. If 'contractType' is present, it must be "PERPETUAL".
                        // 3. If 'contractType' is NOT present, we still include it (assuming it's perpetual
                        //    since we're hitting the futures API base URL).
                        boolean isUsdtPair = symbol.endsWith("USDT");
                        boolean isPerpetualContract = node.has("contractType") && "PERPETUAL".equals(node.get("contractType").asText());
                        boolean isContractTypeMissing = !node.has("contractType");

                        if (isUsdtPair && (isPerpetualContract || isContractTypeMissing)) {
                            CoinData coin = new CoinData();
                            coin.setId(symbol);
                            coin.setName(symbol.replace("USDT", ""));
                            coin.setCurrentPrice(currentPrice);
                            coin.setVolume(BinanceApiClient.formatVolume(volume));
                            highVolumeCoins.add(coin);
                            processedCount++;
                        } else {
                            System.out.println("  DEBUG: Skipping " + symbol + " - isUsdtPair: " + isUsdtPair + ", isPerpetualContract: " + isPerpetualContract + ", isContractTypeMissing: " + isContractTypeMissing);
                        }
                    } catch (Exception e) {
                        System.err.println("Error parsing ticker data for a symbol: " + node.toString() + " | Error:  " + e.getMessage());
                    }
                }
                System.out.println("Finished processing ticker data. Found " + processedCount + " USDT Perpetual Futures symbols before sorting/limiting.");


                return highVolumeCoins.stream()
                        .sorted(Comparator.comparingDouble(c -> BinanceApiClient.parseVolumeString(((CoinData) c).getVolume())).reversed())
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

    // --- Order Block Detection Logic Service (as a static nested class) ---
    @Service
    public static class OrderBlockDetector {

        // We now need 5 candles for our pattern: C0, C1, C2, C3, Current
        private static final int WINDOW_SIZE = 5;

        /**
         * Detects the most recent valid order block within the provided klines based on SMC principles.
         * It iterates backward through the klines to find the latest pattern.
         *
         * @param coin The CoinData for which to detect order blocks.
         * @param klines The list of candlestick data.
         * @param timeframe The timeframe of the klines (e.g., "4h").
         * @param impulsiveMoveMinBodyRatio Minimum ratio of candle body to total range for impulsive move.
         * @param impulsiveMoveMinPriceChange Minimum percentage price change for impulsive move.
         * @param significantVolumeFactor Factor for C2 volume significance relative to median volume.
         * @param requireBOS If true, a Break of Structure is required for detection.
         * @param requireC3ClosePastC2 If true, C3 must close beyond C2's close/open in the direction of the impulse.
         * @param requireFVG If true, a Fair Value Gap is required for detection.
         * @return An OrderBlockResult containing the detected order block, or "None" if no valid OB is found.
         */
        public OrderBlockResult detectOrderBlock(
                CoinData coin,
                List<BinanceApiClient.Candlestick> klines,
                String timeframe,
                double impulsiveMoveMinBodyRatio,
                double impulsiveMoveMinPriceChange,
                double significantVolumeFactor,
                boolean requireBOS,
                boolean requireC3ClosePastC2,
                boolean requireFVG // New parameter
        ) {
            OrderBlockResult defaultResult = new OrderBlockResult();
            defaultResult.setId(coin.getId());
            defaultResult.setName(coin.getName());
            defaultResult.setCurrentPrice(coin.getCurrentPrice());
            defaultResult.setVolume(coin.getVolume());
            defaultResult.setTimestamp(LocalDateTime.now().atZone(ZoneId.systemDefault()).toLocalTime().toString());
            defaultResult.setTimeframe(timeframe);
            defaultResult.setOrderBlockType("None");
            defaultResult.setDetails("No significant " + timeframe.toUpperCase() + " order block detected based on current advanced SMC logic.");


            if (klines == null || klines.size() < WINDOW_SIZE) {
                System.out.println("  DEBUG: " + coin.getId() + " (" + timeframe + ") - Not enough candles for OB analysis. Need >= " + WINDOW_SIZE + ", found " + (klines != null ? klines.size() : 0));
                defaultResult.setDetails("Not enough candlestick data for advanced order block analysis (need at least " + WINDOW_SIZE + " candles).");
                return defaultResult;
            }

            // Calculate median volume for more robust filtering
            List<Double> volumes = klines.stream()
                    .mapToDouble(BinanceApiClient.Candlestick::getVolume)
                    .boxed() // Convert to Double objects for sorting
                    .sorted()
                    .collect(Collectors.toList());
            double medianVolume = 0.0;
            if (!volumes.isEmpty()) {
                if (volumes.size() % 2 == 1) { // Odd number of elements
                    medianVolume = volumes.get(volumes.size() / 2);
                } else { // Even number of elements
                    medianVolume = (volumes.get(volumes.size() / 2 - 1) + volumes.get(volumes.size() / 2)) / 2.0;
                }
            }


            // Iterate backwards from the latest possible window to find the most recent OB
            // 'i' represents the index of the 'currentCandle' (C4 in a 5-candle pattern)
            for (int i = klines.size() - 1; i >= WINDOW_SIZE - 1; i--) {
                BinanceApiClient.Candlestick currentCandle = klines.get(i);       // C4 (latest candle in this window)
                BinanceApiClient.Candlestick impulsiveCandle = klines.get(i - 1);   // C3 (impulsive move candle)
                BinanceApiClient.Candlestick obCandidateCandle = klines.get(i - 2); // C2 (order block candidate candle)
                BinanceApiClient.Candlestick fvgReferenceCandle = klines.get(i - 3); // C1 (FVG reference candle)
                BinanceApiClient.Candlestick bosReferenceCandle = klines.get(i - 4); // C0 (BOS reference candle)

                // Basic sanity check for valid candles (avoid division by zero or nonsensical data)
                if (obCandidateCandle.getVolume() == 0.0 || (obCandidateCandle.getHigh() - obCandidateCandle.getLow()) == 0.0 ||
                        impulsiveCandle.getVolume() == 0.0 || (impulsiveCandle.getHigh() - impulsiveCandle.getLow()) == 0.0 ||
                        fvgReferenceCandle.getVolume() == 0.0 || (fvgReferenceCandle.getHigh() - fvgReferenceCandle.getLow()) == 0.0 ||
                        bosReferenceCandle.getVolume() == 0.0 || (bosReferenceCandle.getHigh() - bosReferenceCandle.getLow()) == 0.0) {
                    System.out.println("  DEBUG: " + coin.getId() + " (" + timeframe + ") - Skipping window ending at index " + i + " due to zero volume/range candle.");
                    continue; // Skip this window and check the previous one
                }

                // --- Bullish Order Block (Buying OB) Detection ---
                // Pattern: C2 (bearish) -> C3 (strong bullish) with FVG between C1 and C3, and BOS (optional)
                boolean c2IsBearish = obCandidateCandle.getClose() < obCandidateCandle.getOpen();
                boolean c3IsBullish = impulsiveCandle.getClose() > impulsiveCandle.getOpen();

                // SMC FVG check (stricter): High of C1 must be below Low of C3
                boolean hasBullishFvg = fvgReferenceCandle.getHigh() < impulsiveCandle.getLow();
                boolean finalBullishFvgCheck = !requireFVG || hasBullishFvg; // Apply new parameter


                // SMC BOS check: C3 closes above the highest high of C0, C1, and C2
                double highestHighBeforeImpulsive = Math.max(bosReferenceCandle.getHigh(), Math.max(fvgReferenceCandle.getHigh(), obCandidateCandle.getHigh()));
                boolean currentHasBullishBOS = impulsiveCandle.getClose() > highestHighBeforeImpulsive;
                boolean finalBullishBOSCheck = !requireBOS || currentHasBullishBOS; // Only require if requireBOS is true


                boolean c3IsStrongImpulsive = isImpulsive(impulsiveCandle, impulsiveMoveMinBodyRatio, impulsiveMoveMinPriceChange, true);
                boolean c3ClosesAboveC2 = impulsiveCandle.getClose() >= obCandidateCandle.getClose(); // C3 closing above C2's close
                boolean finalC3ClosesAboveC2Check = !requireC3ClosePastC2 || c3ClosesAboveC2; // Apply new parameter
                boolean c2VolumeSignificant = medianVolume > 0 && obCandidateCandle.getVolume() > (medianVolume * significantVolumeFactor);

                System.out.println("  DEBUG: " + coin.getId() + " (" + timeframe + ") - Checking Bullish OB for window ending at index " + i + " (Current Price: " + String.format("%.8f", coin.getCurrentPrice()) + ")");
                System.out.println("    C0 (bosRef): H=" + bosReferenceCandle.getHigh() + ", L=" + bosReferenceCandle.getLow());
                System.out.println("    C1 (fvgRef): H=" + fvgReferenceCandle.getHigh() + ", L=" + fvgReferenceCandle.getLow() + ", C=" + fvgReferenceCandle.getClose());
                System.out.println("    C2 (obCand): O=" + obCandidateCandle.getOpen() + ", C=" + obCandidateCandle.getClose() + ", H=" + obCandidateCandle.getHigh() + ", L=" + obCandidateCandle.getLow() + ", V=" + obCandidateCandle.getVolume());
                System.out.println("    C3 (impuls): O=" + impulsiveCandle.getOpen() + ", C=" + impulsiveCandle.getClose() + ", H=" + impulsiveCandle.getHigh() + ", L=" + impulsiveCandle.getLow() + ", V=" + impulsiveCandle.getVolume());
                System.out.println("    Conditions: C2 Bearish=" + c2IsBearish + ", C3 Bullish=" + c3IsBullish + ", FVG=" + hasBullishFvg + " (Required: " + requireFVG + ", Final Check: " + finalBullishFvgCheck + ", C1.H: " + fvgReferenceCandle.getHigh() + ", C3.L: " + impulsiveCandle.getLow() + ")"); // Updated debug
                System.out.println("    BOS=" + finalBullishBOSCheck + " (Requires BOS: " + requireBOS + ", Actual BOS: " + currentHasBullishBOS + ", C3.C: " + impulsiveCandle.getClose() + ", Highest(C0,C1,C2)H: " + highestHighBeforeImpulsive + ")");
                System.out.println("    C3 Impulsive=" + c3IsStrongImpulsive + ", C3 Closes Above C2=" + c3ClosesAboveC2 + " (Required: " + requireC3ClosePastC2 + ", Final Check: " + finalC3ClosesAboveC2Check + ")");
                System.out.println("    C2 Vol Significant=" + c2VolumeSignificant + " (Actual: " + String.format("%.2f", obCandidateCandle.getVolume() / medianVolume) + "x median)");
                System.out.println("    Overall Bullish OB Check: " + (c2IsBearish && c3IsBullish && finalBullishFvgCheck && finalBullishBOSCheck && c3IsStrongImpulsive && finalC3ClosesAboveC2Check && c2VolumeSignificant));


                if (c2IsBearish &&
                        c3IsBullish &&
                        finalBullishFvgCheck && // Apply new parameter
                        finalBullishBOSCheck &&
                        c3IsStrongImpulsive &&
                        finalC3ClosesAboveC2Check &&
                        c2VolumeSignificant
                ) {
                    // Check if the OB is unmitigated by subsequent candles (from C3 up to current)
                    if (isUnmitigated(klines, i - 1, obCandidateCandle, true)) { // i-1 is the index of C3
                        double bullishObPrice = obCandidateCandle.getLow(); // Use low as the key price level
                        double bullishObZoneStart = obCandidateCandle.getOpen();
                        double bullishObZoneEnd = obCandidateCandle.getLow();
                        return makeOBResult(coin, timeframe, "Buying (Bullish)", bullishObPrice, currentCandle,
                                String.format("Last bearish candle (C2) before strong bullish move (C3) with %s FVG %s BOS. OB Zone: $%.4f - $%.4f.",
                                        (requireFVG ? "" : "optional"), (requireBOS ? "&" : "and optional"), bullishObZoneStart, bullishObZoneEnd));
                    } else {
                        System.out.println("  DEBUG: " + coin.getId() + " (" + timeframe + ") - Bullish OB candidate rejected: Tapped by subsequent price action.");
                    }
                }

                // --- Bearish Order Block (Selling OB) Detection ---
                // Pattern: C2 (bullish) -> C3 (strong bearish) with FVG between C1 and C3, and BOS (optional)
                boolean c2IsBullish = obCandidateCandle.getClose() > obCandidateCandle.getOpen();
                boolean c3IsBearish = impulsiveCandle.getClose() < impulsiveCandle.getOpen();

                // SMC FVG check (stricter): Low of C1 must be above High of C3
                boolean hasBearishFvg = fvgReferenceCandle.getLow() > impulsiveCandle.getHigh();
                boolean finalBearishFvgCheck = !requireFVG || hasBearishFvg; // Apply new parameter


                // SMC BOS check: C3 closes below the lowest low of C0, C1, and C2
                double lowestLowBeforeImpulsive = Math.min(bosReferenceCandle.getLow(), Math.min(fvgReferenceCandle.getLow(), obCandidateCandle.getLow()));
                boolean currentHasBearishBOS = impulsiveCandle.getClose() < lowestLowBeforeImpulsive;
                boolean finalBearishBOSCheck = !requireBOS || currentHasBearishBOS; // Only require if requireBOS is true


                boolean c3IsStrongImpulsiveBearish = isImpulsive(impulsiveCandle, impulsiveMoveMinBodyRatio, impulsiveMoveMinPriceChange, false);
                boolean c3ClosesBelowC2 = impulsiveCandle.getClose() <= obCandidateCandle.getClose(); // C3 closing below C2's close
                boolean finalC3ClosesBelowC2Check = !requireC3ClosePastC2 || c3ClosesBelowC2; // Apply new parameter
                boolean c2VolumeSignificantBearish = medianVolume > 0 && obCandidateCandle.getVolume() > (medianVolume * significantVolumeFactor);

                System.out.println("  DEBUG: " + coin.getId() + " (" + timeframe + ") - Checking Bearish OB for window ending at index " + i + " (Current Price: " + String.format("%.8f", coin.getCurrentPrice()) + ")");
                System.out.println("    C0 (bosRef): H=" + bosReferenceCandle.getHigh() + ", L=" + bosReferenceCandle.getLow());
                System.out.println("    C1 (fvgRef): H=" + fvgReferenceCandle.getHigh() + ", L=" + fvgReferenceCandle.getLow() + ", C=" + fvgReferenceCandle.getClose());
                System.out.println("    C2 (obCand): O=" + obCandidateCandle.getOpen() + ", C=" + obCandidateCandle.getClose() + ", H=" + obCandidateCandle.getHigh() + ", L=" + obCandidateCandle.getLow() + ", V=" + obCandidateCandle.getVolume());
                System.out.println("    C3 (impuls): O=" + impulsiveCandle.getOpen() + ", C=" + impulsiveCandle.getClose() + ", H=" + impulsiveCandle.getHigh() + ", L=" + impulsiveCandle.getLow() + ", V=" + impulsiveCandle.getVolume());
                System.out.println("    Conditions: C2 Bullish=" + c2IsBullish + ", C3 Bearish=" + c3IsBearish + ", FVG=" + hasBearishFvg + " (Required: " + requireFVG + ", Final Check: " + finalBearishFvgCheck + ", C1.L: " + fvgReferenceCandle.getLow() + ", C3.H: " + impulsiveCandle.getHigh() + ")"); // Updated debug
                System.out.println("    BOS=" + finalBearishBOSCheck + " (Requires BOS: " + requireBOS + ", Actual BOS: " + currentHasBearishBOS + ", C3.C: " + impulsiveCandle.getClose() + ", Lowest(C0,C1,C2)L: " + lowestLowBeforeImpulsive + ")");
                System.out.println("    C3 Impulsive=" + c3IsStrongImpulsiveBearish + ", C3 Closes Below C2=" + c3ClosesBelowC2 + " (Required: " + requireC3ClosePastC2 + ", Final Check: " + finalC3ClosesBelowC2Check + ")");
                System.out.println("    C2 Vol Significant=" + c2VolumeSignificantBearish + " (Actual: " + String.format("%.2f", obCandidateCandle.getVolume() / medianVolume) + "x median)");
                System.out.println("    Overall Bearish OB Check: " + (c2IsBullish && c3IsBearish && finalBearishFvgCheck && finalBearishBOSCheck && c3IsStrongImpulsiveBearish && finalC3ClosesBelowC2Check && c2VolumeSignificantBearish));


                if (c2IsBullish &&
                        c3IsBearish &&
                        finalBearishFvgCheck && // Apply new parameter
                        finalBearishBOSCheck &&
                        c3IsStrongImpulsiveBearish &&
                        finalC3ClosesBelowC2Check &&
                        c2VolumeSignificantBearish
                ) {
                    // Check if the OB is unmitigated by subsequent candles (from C3 up to current)
                    if (isUnmitigated(klines, i - 1, obCandidateCandle, false)) { // i-1 is the index of C3
                        double bearishObPrice = obCandidateCandle.getHigh(); // Use high as the key price level
                        double bearishObZoneStart = obCandidateCandle.getOpen();
                        double bearishObZoneEnd = obCandidateCandle.getHigh();
                        return makeOBResult(coin, timeframe, "Selling (Bearish)", bearishObPrice, currentCandle,
                                String.format("Last bullish candle (C2) before strong bearish move (C3) with %s FVG %s BOS. OB Zone: $%.4f - $%.4f.",
                                        (requireFVG ? "" : "optional"), (requireBOS ? "&" : "and optional"), bearishObZoneStart, bearishObZoneEnd));
                    } else {
                        System.out.println("  DEBUG: " + coin.getId() + " (" + timeframe + ") - Bearish OB candidate rejected: Tapped by subsequent price action.");
                    }
                }
            }

            // If loop finishes without finding any valid unmitigated OB, return the default "None" result
            return defaultResult;
        }

        /**
         * Helper method to check if a candle represents an impulsive move.
         * An impulsive move has a significant body size relative to its total range and a notable price change.
         *
         * @param candle The candlestick to check.
         * @param minBodyRatio Minimum ratio of candle body to total range (0 to 1).
         * @param minPriceChange Minimum absolute percentage price change.
         * @param bullish True if checking for a bullish impulsive move, false for bearish.
         * @return True if the candle is impulsive, false otherwise.
         */
        private boolean isImpulsive(BinanceApiClient.Candlestick candle, double minBodyRatio, double minPriceChange, boolean bullish) {
            double priceChange = bullish ?
                    (candle.getClose() - candle.getOpen()) / candle.getOpen() :
                    (candle.getOpen() - candle.getClose()) / candle.getOpen();
            double bodySize = abs(candle.getClose() - candle.getOpen());
            double range = candle.getHigh() - candle.getLow();
            double bodyRatio = (range > 0) ? (bodySize / range) : 0; // Avoid division by zero

            // Ensure priceChange is positive for the direction being checked
            return priceChange > minPriceChange && bodyRatio > minBodyRatio;
        }

        /**
         * Checks if an order block has been mitigated (tapped) by subsequent price action.
         *
         * @param klines The full list of candlestick data.
         * @param impulsiveCandleIndex The index of the impulsive candle (C3) in the klines list.
         * @param obCandidateCandle The order block candidate candle (C2).
         * @param isBullish True if checking for a bullish OB, false for bearish.
         * @return True if the order block is unmitigated, false otherwise.
         */
        private boolean isUnmitigated(List<BinanceApiClient.Candlestick> klines, int impulsiveCandleIndex, BinanceApiClient.Candlestick obCandidateCandle, boolean isBullish) {
            double obZoneStart;
            double obZoneEnd;

            if (isBullish) {
                // Bullish OB zone: from C2's open to C2's low
                obZoneStart = obCandidateCandle.getOpen();
                obZoneEnd = obCandidateCandle.getLow();
            } else {
                // Bearish OB zone: from C2's open to C2's high
                obZoneStart = obCandidateCandle.getOpen();
                obZoneEnd = obCandidateCandle.getHigh();
            }

            // Ensure start is less than end for range comparison
            double lowerBound = Math.min(obZoneStart, obZoneEnd);
            double upperBound = Math.max(obZoneStart, obZoneEnd);

            // Iterate through candles AFTER the impulsive candle (C3) up to the latest candle
            for (int k = impulsiveCandleIndex + 1; k < klines.size(); k++) {
                BinanceApiClient.Candlestick subsequentCandle = klines.get(k);

                // Check if the subsequent candle's wick (or body) has entered or passed through the OB zone
                // The zone is from lowerBound to upperBound
                boolean tapped = (subsequentCandle.getLow() <= upperBound && subsequentCandle.getHigh() >= lowerBound);

                if (tapped) {
                    System.out.println("    DEBUG: OB mitigated by candle at index " + k + " (Low: " + subsequentCandle.getLow() + ", High: " + subsequentCandle.getHigh() + ") vs OB Zone [" + lowerBound + ", " + upperBound + "]");
                    return false; // Mitigated
                }
            }
            return true; // Not mitigated
        }


        /**
         * Helper method to create an OrderBlockResult object.
         *
         * @param coin The base coin data.
         * @param timeframe The timeframe of the detection.
         * @param type The type of order block ("Buying (Bullish)" or "Selling (Bearish)").
         * @param price The detected order block price level.
         * @param currentCandle The current candle at the time of detection (for timestamp).
         * @param specificDetails Specific details about the detection.
         * @return A populated OrderBlockResult object.
         */
        private OrderBlockResult makeOBResult(CoinData coin, String timeframe, String type, double price, BinanceApiClient.Candlestick currentCandle, String specificDetails) {
            OrderBlockResult result = new OrderBlockResult();
            result.setId(coin.getId());
            result.setName(coin.getName());
            result.setCurrentPrice(coin.getCurrentPrice());
            result.setVolume(coin.getVolume());
            result.setOrderBlockType(type);
            result.setOrderBlockPrice(price);
            // Use current system time for timestamp, as candle time might be in the past
            result.setTimestamp(LocalDateTime.now().atZone(ZoneId.systemDefault()).toLocalTime().toString());
            result.setTimeframe(timeframe);
            result.setDetails(
                    String.format("Potential %s Order Block detected near $%.2f. %s Current price: $%.2f",
                            timeframe.toUpperCase(), price, specificDetails, coin.getCurrentPrice())
            );
            return result;
        }
    }

    // --- REST Controller (as a static nested class) ---
    @RestController
    @RequestMapping("/api")
    @CrossOrigin(origins = "*") // Allows requests from any origin for local development
    public static class CryptoController {

        @Autowired
        private BinanceApiClient binanceApiClient;
        @Autowired
        private OrderBlockDetector orderBlockDetector;

        // Using ConcurrentMap for thread-safe storage of latest scan results
        private final ConcurrentMap<String, OrderBlockResult> latestScanResults = new ConcurrentHashMap<>();

        /**
         * API endpoint to perform a one-time scan for order blocks on Binance Futures.
         * It fetches top volume coins, then their klines, and applies order block detection.
         *
         * @param interval          The candlestick interval (e.g., "1h", "4h", "1d"). Default is "4h".
         * @param minBodyRatio      Minimum ratio of candle body to total range for impulsive move. Default is 0.15.
         * @param minPriceChange    Minimum percentage price change for impulsive move. Default is 0.0002.
         * @param volumeFactor      Factor for C2 volume significance. Default is 0.5.
         * @param requireBOS        If true, a Break of Structure is required for detection. Default is true.
         * @param requireC3ClosePastC2 If true, C3 must close beyond C2's close/open in the direction of the impulse.
         * @param requireFVG        If true, a Fair Value Gap is required for detection. Default is true.
         * @return A list of OrderBlockResult objects containing scan findings.
         */
        @GetMapping("/scan-order-blocks")
        public List<OrderBlockResult> getOrderBlockScanResults(
                @RequestParam(defaultValue = "4h") String interval,
                @RequestParam(defaultValue = "0.15") double minBodyRatio,
                @RequestParam(defaultValue = "0.0002") double minPriceChange,
                @RequestParam(defaultValue = "0.5") double volumeFactor,
                @RequestParam(defaultValue = "true") boolean requireBOS,
                @RequestParam(defaultValue = "true") boolean requireC3ClosePastC2,
                @RequestParam(defaultValue = "true") boolean requireFVG // New request parameter
        ) {
            System.out.println("API endpoint hit: Performing one-time scan for order blocks on Binance Futures for interval: " + interval);
            System.out.println(String.format("  Parameters: minBodyRatio=%.4f, minPriceChange=%.4f, volumeFactor=%.2f, requireBOS=%b, requireC3ClosePastC2=%b, requireFVG=%b", minBodyRatio, minPriceChange, volumeFactor, requireBOS, requireC3ClosePastC2, requireFVG));

            List<OrderBlockResult> results = new ArrayList<>();
            try {
                List<CoinData> highVolumeCoins = binanceApiClient.getTopVolumeFuturesCoins(100);
                System.out.println("Found " + highVolumeCoins.size() + " top volume futures coins.");

                for (CoinData coin : highVolumeCoins) {
                    List<BinanceApiClient.Candlestick> klines = binanceApiClient.getCandlestickData(coin.getId(), interval, 200); // Fetch more klines for iterative scan
                    if (klines != null && klines.size() >= OrderBlockDetector.WINDOW_SIZE) {
                        OrderBlockResult result = orderBlockDetector.detectOrderBlock(
                                coin, klines, interval, minBodyRatio, minPriceChange, volumeFactor, requireBOS, requireC3ClosePastC2, requireFVG // Pass new parameter
                        );
                        results.add(result);
                        latestScanResults.put(coin.getId(), result); // Update cache
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
                        noDataResult.setDetails("Insufficient candlestick data for analysis.");
                        noDataResult.setTimeframe(interval);
                        results.add(noDataResult);
                        latestScanResults.put(coin.getId(), noDataResult); // Update cache
                    }
                }
            } catch (IOException e) {
                System.err.println("Error during one-time scan: " + e.getMessage());
                e.printStackTrace();
                return new ArrayList<>();
            }
            return results;
        }

        // This method is for scheduled background scanning, not directly called by frontend
        public void performScheduledScan() {
            System.out.println("Performing scheduled background scan for order blocks on Binance Futures...");
            try {
                List<CoinData> highVolumeCoins = binanceApiClient.getTopVolumeFuturesCoins(100);
                System.out.println("Found " + highVolumeCoins.size() + " top volume futures coins for scheduled scan.");

                // For scheduled scan, we might use default or a specific set of parameters
                // Here, using 'mid' sensitivity parameters for background scan
                double defaultMinBodyRatio = 0.15;
                double defaultMinPriceChange = 0.0005;
                double defaultVolumeFactor = 0.6;
                String defaultTimeframe = "4h";
                boolean defaultRequireBOS = true; // Scheduled scan will still require BOS by default
                boolean defaultRequireC3ClosePastC2 = true; // Scheduled scan will still require C3 close past C2 by default
                boolean defaultRequireFVG = true; // Scheduled scan will still require FVG by default

                for (CoinData coin : highVolumeCoins) {
                    List<BinanceApiClient.Candlestick> klines = binanceApiClient.getCandlestickData(coin.getId(), defaultTimeframe, 200);
                    if (klines != null && klines.size() >= OrderBlockDetector.WINDOW_SIZE) {
                        OrderBlockResult result = orderBlockDetector.detectOrderBlock(
                                coin, klines, defaultTimeframe, defaultMinBodyRatio, defaultMinPriceChange, defaultVolumeFactor, defaultRequireBOS, defaultRequireC3ClosePastC2, defaultRequireFVG
                        );
                        latestScanResults.put(coin.getId(), result); // Update the cache
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
                        latestScanResults.put(coin.getId(), noDataResult); // Update cache
                    }
                }
            } catch (IOException e) {
                System.err.println("Error during scheduled scan: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // --- Scheduled Task (as a static nested class) ---
    @Service
    public static class ScanScheduler {

        @Autowired
        private CryptoController cryptoController;

        // Schedule the scan to run every 4 hours (14400000 ms)
        // This will update the `latestScanResults` in CryptoController periodically.
        @Scheduled(fixedRate = 14400000) // Runs every 4 hours (4 * 60 * 60 * 1000 ms)
        public void scheduleFixedRateScan() {
            cryptoController.performScheduledScan();
        }
    }
}
