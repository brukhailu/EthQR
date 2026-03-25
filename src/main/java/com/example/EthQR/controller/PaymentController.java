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
        
        String transactionId = (clientTransactionId != null && !clientTransactionId.isEmpty()) 
                ? clientTransactionId 
                : UUID.randomUUID().toString();

        try {
            Map<String, String> paymentResult = paymentService.processPayment(qrData, transactionId);
            Map<String, String> responseBody = Map.of(
                "status", "SUCCESS",
                "response", paymentResult.get("response"),
                "transactionId", paymentResult.get("endToEndId") // Return the correct ID
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
    public ResponseEntity<List<String>> getLogs(@PathVariable("transactionId") String transactionId) {
        return ResponseEntity.ok(transactionLogger.getLogs(transactionId));
    }
}
