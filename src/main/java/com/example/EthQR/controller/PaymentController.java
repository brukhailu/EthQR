package com.example.EthQR.controller;

import com.example.EthQR.model.QRCodeData;
import com.example.EthQR.model.PaymentRequest;
import com.example.EthQR.service.PaymentService;
import com.example.EthQR.service.TransactionLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${payment.env.default:45}")
    private String defaultEnv;

    @PostMapping("/get-token")
    public ResponseEntity<?> getToken() {
        try {
            String tempId = UUID.randomUUID().toString();
            String accessToken = paymentService.getAccessToken(tempId, defaultEnv);
            return ResponseEntity.ok(Collections.singletonMap("access_token", accessToken));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    @PostMapping("/process")
    public ResponseEntity<?> processPayment(
            @RequestBody Map<String, Object> requestBody,
            @RequestHeader(value = "X-Transaction-Id", required = false) String clientTransactionId) {
        
        String transactionId = (clientTransactionId != null && !clientTransactionId.isEmpty()) 
                ? clientTransactionId 
                : UUID.randomUUID().toString();

        String env = requestBody.containsKey("env") ? requestBody.get("env").toString() : defaultEnv;
        boolean skipMandatoryQrValidation = (boolean) requestBody.getOrDefault("skipMandatoryQrValidation", false);

        try {
            QRCodeData qrData;
            PaymentRequest userInput;

            if (requestBody.containsKey("qrCodeData")) {
                qrData = objectMapper.convertValue(requestBody.get("qrCodeData"), QRCodeData.class);
            } else {
                qrData = objectMapper.convertValue(requestBody, QRCodeData.class);
            }

            if (requestBody.containsKey("paymentRequest")) {
                userInput = objectMapper.convertValue(requestBody.get("paymentRequest"), PaymentRequest.class);
            } else {
                userInput = objectMapper.convertValue(requestBody, PaymentRequest.class);
            }

            Map<String, String> paymentResult = paymentService.processPayment(qrData, userInput, transactionId, env, skipMandatoryQrValidation);
            Map<String, String> responseBody = Map.of(
                "status", "SUCCESS",
                "response", paymentResult.get("response"),
                "transactionId", paymentResult.get("txId") 
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
