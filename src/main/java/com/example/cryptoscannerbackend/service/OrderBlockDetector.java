package com.example.cryptoscannerbackend.service;

import com.example.cryptoscannerbackend.model.CoinData;
import com.example.cryptoscannerbackend.model.OrderBlockResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.Math.abs;

@Service
public class OrderBlockDetector {

    public static final int WINDOW_SIZE = 5; // Public static for easy access

    public OrderBlockResult detectOrderBlock(
            CoinData coin,
            List<BinanceApiClient.Candlestick> klines,
            String timeframe,
            double impulsiveMoveMinBodyRatio,
            double impulsiveMoveMinPriceChange,
            double significantVolumeFactor,
            boolean requireBOS,
            boolean requireC3ClosePastC2,
            boolean requireFVG,
            boolean requireUnmitigated,
            double minFvgDepthRatio
    ) {
        OrderBlockResult defaultResult = createDefaultResult(coin, timeframe);

        if (klines == null || klines.size() < WINDOW_SIZE) {
            System.out.println("  DEBUG: " + coin.getId() + " (" + timeframe + ") - Not enough candles for OB analysis. Need >= " + WINDOW_SIZE + ", found " + (klines != null ? klines.size() : 0));
            defaultResult.setDetails("Not enough candlestick data for advanced order block analysis (need at least " + WINDOW_SIZE + " candles).");
            return defaultResult;
        }

        List<Double> volumes = klines.stream()
                .mapToDouble(BinanceApiClient.Candlestick::getVolume)
                .boxed()
                .sorted()
                .collect(Collectors.toList());
        double medianVolume = 0.0;
        if (!volumes.isEmpty()) {
            if (volumes.size() % 2 == 1) {
                medianVolume = volumes.get(volumes.size() / 2);
            } else {
                medianVolume = (volumes.get(volumes.size() / 2 - 1) + volumes.get(volumes.size() / 2)) / 2.0;
            }
        }


        for (int i = klines.size() - 1; i >= WINDOW_SIZE - 1; i--) {
            // C0, C1, C2, C3, C4... (from oldest to newest in the window)
            // We are looking for C2 (OB Candidate) and C3 (Impulsive)
            BinanceApiClient.Candlestick currentCandle = klines.get(i); // This is the newest candle, C4 in a 5-candle window
            BinanceApiClient.Candlestick impulsiveCandle = klines.get(i - 1); // C3
            BinanceApiClient.Candlestick obCandidateCandle = klines.get(i - 2); // C2
            BinanceApiClient.Candlestick fvgReferenceCandle = klines.get(i - 3); // C1 (for FVG with C3)
            BinanceApiClient.Candlestick bosReferenceCandle = klines.get(i - 4); // C0 (for BOS with C3)

            // Basic check for valid candle data to avoid division by zero or NaN
            if (obCandidateCandle.getVolume() == 0.0 || (obCandidateCandle.getHigh() - obCandidateCandle.getLow()) == 0.0 ||
                    impulsiveCandle.getVolume() == 0.0 || (impulsiveCandle.getHigh() - impulsiveCandle.getLow()) == 0.0 ||
                    fvgReferenceCandle.getVolume() == 0.0 || (fvgReferenceCandle.getHigh() - fvgReferenceCandle.getLow()) == 0.0 ||
                    bosReferenceCandle.getVolume() == 0.0 || (bosReferenceCandle.getHigh() - bosReferenceCandle.getLow()) == 0.0) {
                System.out.println("  DEBUG: " + coin.getId() + " (" + timeframe + ") - Skipping window ending at index " + i + " due to zero volume/range candle.");
                continue;
            }

            // --- Bullish Order Block (Buying OB) Detection ---
            boolean c2IsBearish = obCandidateCandle.getClose() < obCandidateCandle.getOpen();
            boolean c3IsBullish = impulsiveCandle.getClose() > impulsiveCandle.getOpen();

            boolean hasBullishFvg = fvgReferenceCandle.getHigh() < impulsiveCandle.getLow();
            boolean finalBullishFvgCheck = !requireFVG || hasBullishFvg;

            double bullishFvgHeight = impulsiveCandle.getLow() - fvgReferenceCandle.getHigh();
            double impulsiveCandleRange = impulsiveCandle.getHigh() - impulsiveCandle.getLow();
            boolean hasSufficientBullishFvgDepth = (impulsiveCandleRange > 0) && (bullishFvgHeight / impulsiveCandleRange) >= minFvgDepthRatio;
            boolean finalBullishFvgDepthCheck = !requireFVG || hasSufficientBullishFvgDepth;


            double highestHighBeforeImpulsive = Math.max(bosReferenceCandle.getHigh(), Math.max(fvgReferenceCandle.getHigh(), obCandidateCandle.getHigh()));
            boolean currentHasBullishBOS = impulsiveCandle.getClose() > highestHighBeforeImpulsive;
            boolean finalBullishBOSCheck = !requireBOS || currentHasBullishBOS;


            boolean c3IsStrongImpulsive = isImpulsive(impulsiveCandle, impulsiveMoveMinBodyRatio, impulsiveMoveMinPriceChange, true);
            boolean c3ClosesAboveC2 = impulsiveCandle.getClose() >= obCandidateCandle.getClose();
            boolean finalC3ClosesAboveC2Check = !requireC3ClosePastC2 || c3ClosesAboveC2;
            boolean c2VolumeSignificant = medianVolume > 0 && obCandidateCandle.getVolume() > (medianVolume * significantVolumeFactor);

            boolean isCurrentObUnmitigated = isUnmitigated(klines, i - 1, obCandidateCandle, true);
            boolean finalUnmitigatedBullishCheck = !requireUnmitigated || isCurrentObUnmitigated;

            System.out.println("  DEBUG: " + coin.getId() + " (" + timeframe + ") - Checking Bullish OB for window ending at index " + i + " (Current Price: " + String.format("%.8f", coin.getCurrentPrice()) + ")");
            System.out.println("    C0 (bosRef): H=" + bosReferenceCandle.getHigh() + ", L=" + bosReferenceCandle.getLow());
            System.out.println("    C1 (fvgRef): H=" + fvgReferenceCandle.getHigh() + ", L=" + fvgReferenceCandle.getLow() + ", C=" + fvgReferenceCandle.getClose());
            System.out.println("    C2 (obCand): O=" + obCandidateCandle.getOpen() + ", C=" + obCandidateCandle.getClose() + ", H=" + obCandidateCandle.getHigh() + ", L=" + obCandidateCandle.getLow() + ", V=" + obCandidateCandle.getVolume());
            System.out.println("    C3 (impuls): O=" + impulsiveCandle.getOpen() + ", C=" + impulsiveCandle.getClose() + ", H=" + impulsiveCandle.getHigh() + ", L=" + impulsiveCandle.getLow() + ", V=" + impulsiveCandle.getVolume());
            System.out.println("    Conditions: C2 Bearish=" + c2IsBearish + ", C3 Bullish=" + c3IsBullish + ", FVG=" + hasBullishFvg + " (Required: " + requireFVG + ", Final Check: " + finalBullishFvgCheck + ", C1.H: " + fvgReferenceCandle.getHigh() + ", C3.L: " + impulsiveCandle.getLow() + ")");
            System.out.println("    FVG Depth=" + hasSufficientBullishFvgDepth + " (Required: " + requireFVG + ", Min Ratio: " + minFvgDepthRatio + ", Actual Ratio: " + String.format("%.4f", (impulsiveCandleRange > 0 ? bullishFvgHeight / impulsiveCandleRange : 0)) + ")");
            System.out.println("    BOS=" + finalBullishBOSCheck + " (Requires BOS: " + requireBOS + ", Actual BOS: " + currentHasBullishBOS + ", C3.C: " + impulsiveCandle.getClose() + ", Highest(C0,C1,C2)H: " + highestHighBeforeImpulsive + ")");
            System.out.println("    C3 Impulsive=" + c3IsStrongImpulsive + ", C3 Closes Above C2=" + c3ClosesAboveC2 + " (Required: " + requireC3ClosePastC2 + ", Final Check: " + finalC3ClosesAboveC2Check + ")");
            System.out.println("    C2 Vol Significant=" + c2VolumeSignificant + " (Actual: " + String.format("%.2f", obCandidateCandle.getVolume() / medianVolume) + "x median)");
            System.out.println("    Unmitigated=" + isCurrentObUnmitigated + " (Required: " + requireUnmitigated + ", Final Check: " + finalUnmitigatedBullishCheck + ")");
            System.out.println("    Overall Bullish OB Check: " + (c2IsBearish && c3IsBullish && finalBullishFvgCheck && finalBullishBOSCheck && c3IsStrongImpulsive && finalC3ClosesAboveC2Check && c2VolumeSignificant && finalUnmitigatedBullishCheck && finalBullishFvgDepthCheck));


            if (c2IsBearish &&
                    c3IsBullish &&
                    finalBullishFvgCheck &&
                    finalBullishBOSCheck &&
                    c3IsStrongImpulsive &&
                    finalC3ClosesAboveC2Check &&
                    c2VolumeSignificant &&
                    finalUnmitigatedBullishCheck &&
                    finalBullishFvgDepthCheck
            ) {
                double bullishObPrice = obCandidateCandle.getLow(); // Price level to trade from
                double bullishObZoneStart = obCandidateCandle.getOpen(); // Top of OB candle body
                double bullishObZoneEnd = obCandidateCandle.getLow(); // Bottom of OB candle wick
                return makeOBResult(coin, timeframe, "Buying (Bullish)", bullishObPrice, currentCandle,
                        String.format("Last bearish candle (C2) before strong bullish move (C3) with %s FVG %s BOS. OB Zone: $%.4f - $%.4f. %s",
                                (requireFVG ? "" : "optional"), (requireBOS ? "&" : "and optional"),
                                obCandidateCandle.getOpen(), obCandidateCandle.getLow(), // Use open and low for zone
                                (isCurrentObUnmitigated ? "Unmitigated." : "Mitigated.")),
                        obCandidateCandle.getOpen(), obCandidateCandle.getHigh(), obCandidateCandle.getLow(), obCandidateCandle.getClose(),
                        bullishObZoneStart, bullishObZoneEnd);
            }

            // --- Bearish Order Block (Selling OB) Detection ---
            boolean c2IsBullish = obCandidateCandle.getClose() > obCandidateCandle.getOpen();
            boolean c3IsBearish = impulsiveCandle.getClose() < impulsiveCandle.getOpen();

            boolean hasBearishFvg = fvgReferenceCandle.getLow() > impulsiveCandle.getHigh();
            boolean finalBearishFvgCheck = !requireFVG || hasBearishFvg;

            double bearishFvgHeight = fvgReferenceCandle.getLow() - impulsiveCandle.getHigh();
            impulsiveCandleRange = impulsiveCandle.getHigh() - impulsiveCandle.getLow();
            boolean hasSufficientBearishFvgDepth = (impulsiveCandleRange > 0) && (bearishFvgHeight / impulsiveCandleRange) >= minFvgDepthRatio;
            boolean finalBearishFvgDepthCheck = !requireFVG || hasSufficientBearishFvgDepth;


            double lowestLowBeforeImpulsive = Math.min(bosReferenceCandle.getLow(), Math.min(fvgReferenceCandle.getLow(), obCandidateCandle.getLow()));
            boolean currentHasBearishBOS = impulsiveCandle.getClose() < lowestLowBeforeImpulsive;
            boolean finalBearishBOSCheck = !requireBOS || currentHasBearishBOS;


            boolean c3IsStrongImpulsiveBearish = isImpulsive(impulsiveCandle, impulsiveMoveMinBodyRatio, impulsiveMoveMinPriceChange, false);
            boolean c3ClosesBelowC2 = impulsiveCandle.getClose() <= obCandidateCandle.getClose();
            boolean finalC3ClosesBelowC2Check = !requireC3ClosePastC2 || c3ClosesBelowC2;
            boolean c2VolumeSignificantBearish = medianVolume > 0 && obCandidateCandle.getVolume() > (medianVolume * significantVolumeFactor);

            boolean isCurrentObUnmitigatedBearish = isUnmitigated(klines, i - 1, obCandidateCandle, false);
            boolean finalUnmitigatedBearishCheck = !requireUnmitigated || isCurrentObUnmitigatedBearish;

            System.out.println("  DEBUG: " + coin.getId() + " (" + timeframe + ") - Checking Bearish OB for window ending at index " + i + " (Current Price: " + String.format("%.8f", coin.getCurrentPrice()) + ")");
            System.out.println("    C0 (bosRef): H=" + bosReferenceCandle.getHigh() + ", L=" + bosReferenceCandle.getLow());
            System.out.println("    C1 (fvgRef): H=" + fvgReferenceCandle.getHigh() + ", L=" + fvgReferenceCandle.getLow() + ", C=" + fvgReferenceCandle.getClose());
            System.out.println("    C2 (obCand): O=" + obCandidateCandle.getOpen() + ", C=" + obCandidateCandle.getClose() + ", H=" + obCandidateCandle.getHigh() + ", L=" + obCandidateCandle.getLow() + ", V=" + obCandidateCandle.getVolume());
            System.out.println("    C3 (impuls): O=" + impulsiveCandle.getOpen() + ", C=" + impulsiveCandle.getClose() + ", H=" + impulsiveCandle.getHigh() + ", L=" + impulsiveCandle.getLow() + ", V=" + impulsiveCandle.getVolume());
            System.out.println("    Conditions: C2 Bullish=" + c2IsBullish + ", C3 Bearish=" + c3IsBearish + ", FVG=" + hasBearishFvg + " (Required: " + requireFVG + ", Final Check: " + finalBearishFvgCheck + ", C1.L: " + fvgReferenceCandle.getLow() + ", C3.H: " + impulsiveCandle.getHigh() + ")");
            System.out.println("    FVG Depth=" + hasSufficientBearishFvgDepth + " (Required: " + requireFVG + ", Min Ratio: " + minFvgDepthRatio + ", Actual Ratio: " + String.format("%.4f", (impulsiveCandleRange > 0 ? bearishFvgHeight / impulsiveCandleRange : 0)) + ")");
            System.out.println("    BOS=" + finalBearishBOSCheck + " (Requires BOS: " + requireBOS + ", Actual BOS: " + currentHasBearishBOS + ", C3.C: " + impulsiveCandle.getClose() + ", Lowest(C0,C1,C2)L: " + lowestLowBeforeImpulsive + ")");
            System.out.println("    C3 Impulsive=" + c3IsStrongImpulsiveBearish + ", C3 Closes Below C2=" + c3ClosesBelowC2 + " (Required: " + requireC3ClosePastC2 + ", Final Check: " + finalC3ClosesBelowC2Check + ")");
            System.out.println("    C2 Vol Significant=" + c2VolumeSignificantBearish + " (Actual: " + String.format("%.2f", obCandidateCandle.getVolume() / medianVolume) + "x median)");
            System.out.println("    Unmitigated=" + isCurrentObUnmitigatedBearish + " (Required: " + requireUnmitigated + ", Final Check: " + finalUnmitigatedBearishCheck + ")");
            System.out.println("    Overall Bearish OB Check: " + (c2IsBullish && c3IsBearish && finalBearishFvgCheck && finalBearishBOSCheck && c3IsStrongImpulsiveBearish && finalC3ClosesBelowC2Check && c2VolumeSignificantBearish && finalUnmitigatedBearishCheck && finalBearishFvgDepthCheck));


            if (c2IsBullish &&
                    c3IsBearish &&
                    finalBearishFvgCheck &&
                    finalBearishBOSCheck &&
                    c3IsStrongImpulsiveBearish &&
                    finalC3ClosesBelowC2Check &&
                    c2VolumeSignificantBearish &&
                    finalUnmitigatedBearishCheck &&
                    finalBearishFvgDepthCheck
            ) {
                double bearishObPrice = obCandidateCandle.getHigh(); // Price level to trade from
                double bearishObZoneStart = obCandidateCandle.getOpen(); // Bottom of OB candle body
                double bearishObZoneEnd = obCandidateCandle.getHigh(); // Top of OB candle wick
                return makeOBResult(coin, timeframe, "Selling (Bearish)", bearishObPrice, currentCandle,
                        String.format("Last bullish candle (C2) before strong bearish move (C3) with %s FVG %s BOS. OB Zone: $%.4f - $%.4f. %s",
                                (requireFVG ? "" : "optional"), (requireBOS ? "&" : "and optional"),
                                obCandidateCandle.getOpen(), obCandidateCandle.getHigh(), // Use open and high for zone
                                (isCurrentObUnmitigatedBearish ? "Unmitigated." : "Mitigated.")),
                        obCandidateCandle.getOpen(), obCandidateCandle.getHigh(), obCandidateCandle.getLow(), obCandidateCandle.getClose(),
                        bearishObZoneStart, bearishObZoneEnd);
            }
        }

        return defaultResult;
    }

    private boolean isImpulsive(BinanceApiClient.Candlestick candle, double minBodyRatio, double minPriceChange, boolean isBullish) {
        double open = candle.getOpen();
        double close = candle.getClose();
        double high = candle.getHigh();
        double low = candle.getLow();

        double priceChange = isBullish ? (close - open) / open : (open - close) / open;
        double bodySize = abs(close - open);
        double range = high - low;
        double bodyRatio = (range > 0) ? (bodySize / range) : 0;

        return priceChange > minPriceChange && bodyRatio > minBodyRatio;
    }

    private boolean isUnmitigated(List<BinanceApiClient.Candlestick> klines, int impulsiveCandleIndex, BinanceApiClient.Candlestick obCandidateCandle, boolean isBullish) {
        double obZoneStart;
        double obZoneEnd;

        if (isBullish) {
            obZoneStart = obCandidateCandle.getOpen();
            obZoneEnd = obCandidateCandle.getLow();
        } else { // Bearish
            obZoneStart = obCandidateCandle.getOpen();
            obZoneEnd = obCandidateCandle.getHigh();
        }

        double lowerBound = Math.min(obZoneStart, obZoneEnd);
        double upperBound = Math.max(obZoneStart, obZoneEnd);

        // Check subsequent candles for mitigation
        for (int k = impulsiveCandleIndex + 1; k < klines.size(); k++) {
            BinanceApiClient.Candlestick subsequentCandle = klines.get(k);

            // Check if any part of the subsequent candle overlaps with the OB zone
            boolean tapped = (subsequentCandle.getLow() <= upperBound && subsequentCandle.getHigh() >= lowerBound);

            if (tapped) {
                System.out.println("    DEBUG: OB mitigated by candle at index " + k + " (Low: " + subsequentCandle.getLow() + ", High: " + subsequentCandle.getHigh() + ") vs OB Zone [" + lowerBound + ", " + upperBound + "]");
                return false; // Mitigated
            }
        }
        return true; // Unmitigated
    }


    private OrderBlockResult createDefaultResult(CoinData coin, String timeframe) {
        OrderBlockResult result = new OrderBlockResult();
        result.setId(coin.getId());
        result.setName(coin.getName());
        result.setCurrentPrice(coin.getCurrentPrice());
        result.setVolume(coin.getVolume());
        result.setTimestamp(LocalDateTime.now().atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        result.setTimeframe(timeframe);
        result.setOrderBlockType("None");
        result.setDetails("No significant " + timeframe.toUpperCase() + " order block detected based on current advanced SMC logic.");
        return result;
    }

    private OrderBlockResult makeOBResult(CoinData coin, String timeframe, String type, double price,
                                          BinanceApiClient.Candlestick currentCandle, String specificDetails,
                                          Double obCandleOpen, Double obCandleHigh, Double obCandleLow, Double obCandleClose,
                                          Double obZoneStart, Double obZoneEnd) {
        OrderBlockResult result = new OrderBlockResult();
        result.setId(coin.getId());
        result.setName(coin.getName());
        result.setCurrentPrice(coin.getCurrentPrice());
        result.setVolume(coin.getVolume());
        result.setOrderBlockType(type);
        result.setOrderBlockPrice(price);
        result.setTimestamp(LocalDateTime.now().atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        result.setTimeframe(timeframe);
        result.setDetails(
                String.format("Potential %s Order Block detected near $%.2f. OB Zone: $%.4f - $%.4f. %s Current price: $%.2f",
                        timeframe.toUpperCase(), price, obZoneStart, obZoneEnd, specificDetails, coin.getCurrentPrice())
        );
        result.setObCandleOpen(obCandleOpen);
        result.setObCandleHigh(obCandleHigh);
        result.setObCandleLow(obCandleLow);
        result.setObCandleClose(obCandleClose);
        result.setObZoneStart(obZoneStart); // Set the zone start
        result.setObZoneEnd(obZoneEnd);     // Set the zone end
        return result;
    }
}
