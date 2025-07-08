package com.example.cryptoscannerbackend.service;

import com.example.cryptoscannerbackend.controller.CryptoController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScanScheduler {

    @Autowired
    private CryptoController cryptoController;

    // Schedule the scan to run every 4 hours (14400000 ms)
    // This will update the `latestScanResults` in CryptoController periodically.
    @Scheduled(fixedRate = 14400000) // Runs every 4 hours (4 * 60 * 60 * 1000 ms)
    public void scheduleFixedRateScan() {
        cryptoController.performScheduledScan();
    }
}
