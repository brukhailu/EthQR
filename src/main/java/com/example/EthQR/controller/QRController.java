package com.example.EthQR.controller;

import com.example.EthQR.model.QRCodeData;
import com.example.EthQR.model.TLVTag;
import com.example.EthQR.model.ValidationRequest;
import com.example.EthQR.service.QRService;
import com.example.EthQR.service.ValidationResult;
import com.example.EthQR.service.ValidationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/qr")
public class QRController {

    @Autowired
    private QRService qrService;

    @Autowired
    private ValidationService validationService;

    @GetMapping("/tags")
    public ResponseEntity<Map<String, TLVTag>> getTags() {
        return ResponseEntity.ok(qrService.getTlvTags());
    }

    @GetMapping("/scenarios")
    public ResponseEntity<String> getScenarios() {
        try {
            ClassPathResource resource = new ClassPathResource("scenarios/standard-scenarios.json");
            String scenarios = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(scenarios);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateQRCode(
            @RequestBody QRCodeData data,
            @RequestParam(required = false) Integer scenarioId,
            @RequestParam(required = false, defaultValue = "false") boolean skipMandatoryQrValidation) {
        try {
            boolean isNegativeScenario = (scenarioId != null && scenarioId > 200);
            String tlvString = qrService.generateTLV(data, isNegativeScenario, skipMandatoryQrValidation);
            byte[] qrCodeImage = qrService.generateQRCodeImage(tlvString, 200, 200);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(qrCodeImage);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    @PostMapping("/generate-string")
    public ResponseEntity<?> generateQRCodeString(
            @RequestBody QRCodeData data,
            @RequestParam(required = false) Integer scenarioId,
            @RequestParam(required = false, defaultValue = "false") boolean skipMandatoryQrValidation) {
        try {
            boolean isNegativeScenario = (scenarioId != null && scenarioId > 200);
            String tlvString = qrService.generateTLV(data, isNegativeScenario, skipMandatoryQrValidation);
            return ResponseEntity.ok(tlvString);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    @PostMapping("/parse")
    public ResponseEntity<?> parseQRCode(@RequestBody String rawData) {
        try {
            return ResponseEntity.ok(qrService.parseTLV(rawData));
        } catch (Exception e) {
             return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    @PostMapping("/parse-image")
    public ResponseEntity<Map<String, Object>> parseQRCodeImage(@RequestParam("file") MultipartFile file) {
        try {
            String tlvString = qrService.decodeQRCodeImage(file.getInputStream());
            Map<String, Object> parsedData = qrService.parseTLV(tlvString);
            return ResponseEntity.ok(Map.of("raw", tlvString, "parsed", parsedData));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "Could not decode QR code from image: " + e.getMessage()));
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<List<ValidationResult>> validate(@RequestBody ValidationRequest request) {
        List<ValidationResult> results = validationService.validate(
                request.getXml(),
                request.getQrData(),
                request.getUserInputs(),
                request.getScenario(),
                request.isSkipMandatoryQrValidation()
        );
        return ResponseEntity.ok(results);
    }
}
