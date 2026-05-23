package com.example.EthQR.controller;

import com.example.EthQR.service.QRDetector;
import com.google.zxing.WriterException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@Controller
public class WebController {

    @Autowired
    private QRDetector qrDetector;

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/parser")
    public String parser() {
        return "parser";
    }

    @GetMapping("/certification")
    public String certification() {
        return "certification";
    }

    @GetMapping("/validator")
    public String validator() {
        return "validator";
    }

    @GetMapping("/mobile-simulator")
    public String mobileSimulator() {
        return "mobile-simulator";
    }

    @GetMapping("/mapping-guide")
    public String mappingGuide() {
        return "mapping-guide";
    }

    @PostMapping("/certification/decode-qr-image")
    public ResponseEntity<Map<String, String>> decodeQrImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "No file uploaded."));
        }
        try {
            String decodedText = qrDetector.detectAndDecode(file.getInputStream());
            if (decodedText != null) {
                return ResponseEntity.ok(Collections.singletonMap("decodedText", decodedText));
            } else {
                return ResponseEntity.badRequest().body(Collections.singletonMap("error", "Could not detect or decode QR code from image."));
            }
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.singletonMap("error", "Error processing image: " + e.getMessage()));
        }
    }

    @GetMapping("/certification/generate-qr-image")
    public ResponseEntity<byte[]> generateQrImage(@RequestParam("content") String content) {
        try {
            byte[] qrImage = qrDetector.generateQrImage(content, 100, 100); // Adjust size as needed
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            return new ResponseEntity<>(qrImage, headers, HttpStatus.OK);
        } catch (WriterException | IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null); // Or return an error image/message
        }
    }
}
