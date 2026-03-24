package com.example.EthQR.controller;

import com.example.EthQR.model.QRCodeData;
import com.example.EthQR.service.PaymentService;
import com.example.EthQR.service.TransactionLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private TransactionLogger transactionLogger;

    @PostMapping("/get-token")
    public ResponseEntity<?> getToken() {
        try {
            // Note: This endpoint is standalone, for specific token testing if needed.
            // The process flow handles token internally.
            // We use a temp ID for logging if called directly.
            String tempId = UUID.randomUUID().toString();
            String accessToken = paymentService.getAccessToken(tempId);
            return ResponseEntity.ok(Collections.singletonMap("access_token", accessToken));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    @PostMapping("/process")
    public ResponseEntity<?> processPayment(
            @RequestBody QRCodeData qrData,
            @RequestHeader(value = "X-Transaction-Id", required = false) String clientTransactionId) {
        
        // Use client provided ID or generate new one
        String transactionId = (clientTransactionId != null && !clientTransactionId.isEmpty()) 
                ? clientTransactionId 
                : UUID.randomUUID().toString();

        try {
            String response = paymentService.processPayment(qrData, transactionId);
            Map<String, String> responseBody = Map.of(
                "status", "SUCCESS",
                "response", response,
                "transactionId", transactionId
            );
            return ResponseEntity.ok(responseBody);
        } catch (Exception e) {
            transactionLogger.log(transactionId, "ERROR: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "error", e.getMessage(),
                "transactionId", transactionId
            ));
        }
    }

    @GetMapping("/logs/{transactionId}")
    public ResponseEntity<List<String>> getLogs(@PathVariable String transactionId) {
        return ResponseEntity.ok(transactionLogger.getLogs(transactionId));
    }
}
