package com.example.EthQR.service;

import com.example.EthQR.model.QRCodeData;
import com.example.EthQR.model.TLVTag;
import com.google.zxing.*;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class QRService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    @Autowired
    private TransactionLogger transactionLogger;

    @Autowired
    private ValidationService validationService; // Injected ValidationService

    @Value("${payment.token.url}")
    private String tokenUrl;

    @Value("${payment.jwt.assertion}")
    private String jwtAssertion;

    @Value("${payment.username}")
    private String username;

    @Value("${payment.password}")
    private String password;

    @Value("${payment.digest.url}")
    private String digestUrl;

    @Value("${payment.incoming.url}")
    private String incomingUrl;

    private final Map<String, TLVTag> tlvTags;

    @Autowired
    public QRService(Map<String, TLVTag> tlvTags) {
        this.tlvTags = tlvTags;
    }

    public Map<String, TLVTag> getTlvTags() {
        return tlvTags;
    }

    public String generateTLV(QRCodeData data) {
        return generateTLV(data, false, false); // Default to not a negative scenario, and not skipping validation
    }

    public String generateTLV(QRCodeData data, boolean isCertificationNegativeScenario) {
        return generateTLV(data, isCertificationNegativeScenario, false); // Default to not skipping validation
    }

    public String generateTLV(QRCodeData data, boolean isCertificationNegativeScenario, boolean skipMandatoryQrValidation) {
        Map<String, String> tlvMap = new java.util.TreeMap<>();
        Map<String, Object> qrDataForValidation = new HashMap<>(); // To hold data for ValidationService

        // Tag 00: Payload Format Indicator
        TLVTag tag00Config = tlvTags.get("00");
        String payloadFormatIndicatorValue = data.getPayloadFormatIndicator() != null ? data.getPayloadFormatIndicator() : "01";
        addTLV(tlvMap, "00", processAndValidate(payloadFormatIndicatorValue, tag00Config, isCertificationNegativeScenario));
        qrDataForValidation.put("00", payloadFormatIndicatorValue);

        // Tag 01: Point of Initiation Method
        TLVTag tag01Config = tlvTags.get("01");
        addIfPresent(tlvMap, "01", processAndValidate(data.getPointOfInitiationMethod(), tag01Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getPointOfInitiationMethod() != null) qrDataForValidation.put("01", data.getPointOfInitiationMethod());

        // Tags 02-51: Merchant Account Information
        if (data.getMerchantAccountInformation() != null) {
            Map<String, String> merchantAccountInfoMap = new HashMap<>();
            data.getMerchantAccountInformation().forEach((tag, value) -> {
                TLVTag tlvTag = tlvTags.get(tag);
                if (tlvTag != null) {
                    String processedValue = processAndValidate(value, tlvTag, isCertificationNegativeScenario);
                    addTLV(tlvMap, tag, processedValue);
                    // For sub-TLVs like tag "28", store as nested map for validation service
                    if (tlvTag.isSubTLV() && processedValue != null) {
                        merchantAccountInfoMap.put(tag, processedValue); // Store processed string for sub-TLV
                        qrDataForValidation.put(tag, parseSubTLV(processedValue)); // Parse for validation service
                    } else if (processedValue != null) {
                        qrDataForValidation.put(tag, processedValue);
                    }
                }
            });
        }
        
        // Tag 28 Mandatory Check (This check is now redundant with validateMandatoryQrFields but kept for consistency)
        TLVTag tag28Config = tlvTags.get("28");
        if(tag28Config != null && tag28Config.isMandatory() && !isCertificationNegativeScenario && !skipMandatoryQrValidation) {
             if(data.getMerchantAccountInformation() == null || !data.getMerchantAccountInformation().containsKey("28")) {
                 // This will be caught by validateMandatoryQrFields, but keeping for immediate feedback
                 throw new IllegalArgumentException("Mandatory Tag 28 (EthSwitch) is missing.");
             }
        }

        // Tag 52: Merchant Category Code
        TLVTag tag52Config = tlvTags.get("52");
        addIfPresent(tlvMap, "52", processAndValidate(data.getMerchantCategoryCode(), tag52Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getMerchantCategoryCode() != null) qrDataForValidation.put("52", data.getMerchantCategoryCode());

        // Tag 53: Transaction Currency
        TLVTag tag53Config = tlvTags.get("53");
        String transactionCurrencyValue = data.getTransactionCurrency() != null ? data.getTransactionCurrency() : "230";
        addTLV(tlvMap, "53", processAndValidate(transactionCurrencyValue, tag53Config, isCertificationNegativeScenario));
        qrDataForValidation.put("53", transactionCurrencyValue);

        // Tag 54: Transaction Amount
        TLVTag tag54Config = tlvTags.get("54");
        addIfPresent(tlvMap, "54", processAndValidate(data.getTransactionAmount(), tag54Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getTransactionAmount() != null) qrDataForValidation.put("54", data.getTransactionAmount());

        // Tag 55: Tip or Convenience Indicator
        TLVTag tag55Config = tlvTags.get("55");
        addIfPresent(tlvMap, "55", processAndValidate(data.getTipOrConvenienceIndicator(), tag55Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getTipOrConvenienceIndicator() != null) qrDataForValidation.put("55", data.getTipOrConvenienceIndicator());

        // Tag 56: Value of Convenience Fee Fixed
        TLVTag tag56Config = tlvTags.get("56");
        addIfPresent(tlvMap, "56", processAndValidate(data.getValueOfConvenienceFeeFixed(), tag56Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getValueOfConvenienceFeeFixed() != null) qrDataForValidation.put("56", data.getValueOfConvenienceFeeFixed());

        // Tag 57: Value of Convenience Fee Percentage
        TLVTag tag57Config = tlvTags.get("57");
        addIfPresent(tlvMap, "57", processAndValidate(data.getValueOfConvenienceFeePercentage(), tag57Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getValueOfConvenienceFeePercentage() != null) qrDataForValidation.put("57", data.getValueOfConvenienceFeePercentage());

        // Tag 58: Country Code
        TLVTag tag58Config = tlvTags.get("58");
        String countryCodeValue = data.getCountryCode() != null ? data.getCountryCode() : "ET";
        addTLV(tlvMap, "58", processAndValidate(countryCodeValue, tag58Config, isCertificationNegativeScenario));
        qrDataForValidation.put("58", countryCodeValue);

        // Tag 59: Merchant Name
        TLVTag tag59Config = tlvTags.get("59");
        addIfPresent(tlvMap, "59", processAndValidate(data.getMerchantName(), tag59Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getMerchantName() != null) qrDataForValidation.put("59", data.getMerchantName());

        // Tag 60: Merchant City
        TLVTag tag60Config = tlvTags.get("60");
        addIfPresent(tlvMap, "60", processAndValidate(data.getMerchantCity(), tag60Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getMerchantCity() != null) qrDataForValidation.put("60", data.getMerchantCity());

        // Tag 61: Postal Code
        TLVTag tag61Config = tlvTags.get("61");
        addIfPresent(tlvMap, "61", processAndValidate(data.getPostalCode(), tag61Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getPostalCode() != null) qrDataForValidation.put("61", data.getPostalCode());

        // Tag 62: Additional Data Field Template
        if (data.getAdditionalDataField() != null && !data.getAdditionalDataField().isEmpty()) {
            TLVTag tag62Config = tlvTags.get("62");
            validateSubTags(data.getAdditionalDataField(), tag62Config, isCertificationNegativeScenario);
            String subTlv62 = buildSubTLV(data.getAdditionalDataField());
            addTLV(tlvMap, "62", subTlv62);
            qrDataForValidation.put("62", data.getAdditionalDataField()); // Store as map for validation service
        }

        // Tag 64: Merchant Information - Language Template
        if (data.getMerchantInformationLanguageTemplate() != null && !data.getMerchantInformationLanguageTemplate().isEmpty()) {
            TLVTag tag64Config = tlvTags.get("64");
            validateSubTags(data.getMerchantInformationLanguageTemplate(), tag64Config, isCertificationNegativeScenario);
            String subTlv64 = buildSubTLV(data.getMerchantInformationLanguageTemplate());
            addTLV(tlvMap, "64", subTlv64);
            qrDataForValidation.put("64", data.getMerchantInformationLanguageTemplate()); // Store as map for validation service
        }

        // Tag 80: Context of Transaction
        TLVTag tag80Config = tlvTags.get("80");
        addIfPresent(tlvMap, "80", processAndValidate(data.getContextOfTransaction(), tag80Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getContextOfTransaction() != null) qrDataForValidation.put("80", data.getContextOfTransaction());

        // Tag 81: Discounts & Loyalty Programs
        TLVTag tag81Config = tlvTags.get("81");
        addIfPresent(tlvMap, "81", processAndValidate(data.getDiscountsAndLoyalty(), tag81Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getDiscountsAndLoyalty() != null) qrDataForValidation.put("81", data.getDiscountsAndLoyalty());

        // Tag 82: Offline to Online
        TLVTag tag82Config = tlvTags.get("82");
        addIfPresent(tlvMap, "82", processAndValidate(data.getOfflineToOnline(), tag82Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getOfflineToOnline() != null) qrDataForValidation.put("82", data.getOfflineToOnline());

        // Tag 83: E-Commerce
        TLVTag tag83Config = tlvTags.get("83");
        addIfPresent(tlvMap, "83", processAndValidate(data.getEcommerce(), tag83Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getEcommerce() != null) qrDataForValidation.put("83", data.getEcommerce());

        // Tag 84: UETR
        TLVTag tag84Config = tlvTags.get("84");
        addIfPresent(tlvMap, "84", processAndValidate(data.getUetr(), tag84Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
        if (data.getUetr() != null) qrDataForValidation.put("84", data.getUetr());

        // Tag 85: Transaction Type Code
        TLVTag tag85Config = tlvTags.get("85");
        Object ttcValue = data.getTransactionTypeCode();
        if (ttcValue != null) {
            if (ttcValue instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> ttcMap = (Map<String, String>) ttcValue;
                String subTlv85 = buildSubTLV(ttcMap);
                addTLV(tlvMap, "85", subTlv85);
                qrDataForValidation.put("85", ttcMap);
            } else {
                String ttcStr = String.valueOf(ttcValue);
                addIfPresent(tlvMap, "85", processAndValidate(ttcStr, tag85Config, isCertificationNegativeScenario), isCertificationNegativeScenario);
                qrDataForValidation.put("85", ttcStr);
            }
        }

        // Unreserved Templates (Any other tag in range 80-99 handled generically if not specific)
        if (data.getUnreservedTemplates() != null) {
            data.getUnreservedTemplates().forEach((tag, subTags) -> {
                // Skip if we already handled it explicitly above (e.g. 80-85)
                if(!tlvMap.containsKey(tag) && tlvTags.containsKey(tag)) {
                    TLVTag config = tlvTags.get(tag);
                    validateSubTags(subTags, config, isCertificationNegativeScenario);
                    String subTlv = buildSubTLV(subTags);
                    addTLV(tlvMap, tag, subTlv);
                    qrDataForValidation.put(tag, subTags); // Store as map for validation service
                }
            });
        }

        // Exclude CRC (Tag 63) from mandatory field validation as it's auto-calculated
        qrDataForValidation.remove("63");

        // Perform strict mandatory field validation using ValidationService
        List<ValidationResult> validationResults = validationService.validateMandatoryQrFields(qrDataForValidation, skipMandatoryQrValidation);
        for (ValidationResult result : validationResults) {
            if ("M".equals(result.getType()) && "MISSING".equals(result.getStatus())) {
                throw new IllegalArgumentException("Mandatory field missing: " + result.getRemarks());
            }
        }

        StringBuilder tlvString = new StringBuilder();
        tlvMap.values().forEach(tlvString::append);

        if (tlvString.length() > 512) {
            throw new IllegalArgumentException("QR payload exceeds 512 characters");
        }

        String contentWithCRC = tlvString.toString() + "6304";
        String finalCrc;
        if (data.getCrc() != null && !data.getCrc().isEmpty()) {
            finalCrc = data.getCrc(); // Use manually provided CRC
        } else {
            finalCrc = calculateCRC(contentWithCRC); // Auto-calculate CRC
        }
        
        return contentWithCRC + finalCrc;
    }

    private String processAndValidate(String value, TLVTag config, boolean isCertificationNegativeScenario) {
        if (config == null) return value;

        // The strict mandatory check is now handled by ValidationService.validateMandatoryQrFields
        // This part remains for other validation rules (length, pattern, etc.)
        if (value == null || value.isEmpty()) {
            return null; // Let ValidationService handle mandatory missing
        }
        
        if (!config.isSubTLV()) {
             validateField(value, config);
        }

        if (config.isSubTLV() && config.getDelimiter() != null) {
            String[] parts = value.split(Pattern.quote(config.getDelimiter()), -1);
            Map<String, String> subTags = new java.util.TreeMap<>();
            
            if (config.getSubTags() != null) {
                for (int i = 0; i < config.getSubTags().size(); i++) {
                    TLVTag subConfig = config.getSubTags().get(i);
                    String subValue = (i < parts.length) ? parts[i] : "";
                    
                    if(!subValue.isEmpty()) {
                        validateField(subValue, subConfig);
                        subTags.put(subConfig.getTag(), subValue);
                    }
                }
            }
            return buildSubTLV(subTags);
        }
        
        return value;
    }

    private void validateSubTags(Map<String, String> data, TLVTag parentConfig, boolean isCertificationNegativeScenario) {
        if (parentConfig == null || parentConfig.getSubTags() == null) return;

        for (TLVTag subConfig : parentConfig.getSubTags()) {
            String val = data.get(subConfig.getTag());
            if (val != null && !val.isEmpty()) {
                validateField(val, subConfig);
            }
        }
    }

    private void validateField(String value, TLVTag config) {
        if (value == null) return;

        if (config.getDataType() != null) {
            switch (config.getDataType()) {
                case "N": 
                    if (!value.matches("^\\d+$")) {
                        throw new IllegalArgumentException(String.format("Field '%s' (Tag %s) must be numeric. Found: '%s'", config.getName(), config.getTag(), value));
                    }
                    break;
                case "A": 
                    if (!value.matches("^[a-zA-Z\\s]+$")) {
                        throw new IllegalArgumentException(String.format("Field '%s' (Tag %s) must be alphabetic. Found: '%s'", config.getName(), config.getTag(), value));
                    }
                    break;
            }
        }

        if (config.getMinLength() != null && value.length() < config.getMinLength()) {
            throw new IllegalArgumentException(String.format("Field '%s' (Tag %s) is too short. Min: %d, Actual: %d.", config.getName(), config.getTag(), config.getMinLength(), value.length()));
        }
        if (config.getMaxLength() != null && value.length() > config.getMaxLength()) {
            throw new IllegalArgumentException(String.format("Field '%s' (Tag %s) is too long. Max: %d, Actual: %d.", config.getName(), config.getTag(), config.getMaxLength(), value.length()));
        }
        if (config.getPattern() != null && !Pattern.matches(config.getPattern(), value)) {
            throw new IllegalArgumentException(String.format("Field '%s' (Tag %s) invalid format. Value: '%s'", config.getName(), config.getTag(), value));
        }
    }

    private void addIfPresent(Map<String, String> map, String tag, String value, boolean isCertificationNegativeScenario) {
        if (value != null && !value.isEmpty()) {
            addTLV(map, tag, value);
        }
        // Mandatory check is now handled by ValidationService.validateMandatoryQrFields
    }

    private String buildSubTLV(Map<String, String> subTags) {
        StringBuilder subTlv = new StringBuilder();
        new java.util.TreeMap<>(subTags).forEach((k, v) -> {
            subTlv.append(k)
                    .append(String.format("%02d", v.length()))
                    .append(v);
        });
        return subTlv.toString();
    }

    private void addTLV(Map<String, String> map, String tag, String value) {
        if (value == null || value.isEmpty()) return;
        String length = String.format("%02d", value.length());
        map.put(tag, tag + length + value);
    }

    public byte[] generateQRCodeImage(String text, int width, int height) throws Exception {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height, hints);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", out);
        return out.toByteArray();
    }

    public String decodeQRCodeImage(InputStream stream) throws Exception {
        QRDetector detector = new QRDetector();
        String decodedText = detector.detectAndDecode(stream);

        if (decodedText == null || decodedText.isEmpty()) {
            throw new Exception("No QR code found in the image. Please ensure the image contains a clear QR code.");
        }

        return decodedText;
    }

    public String calculateCRC(String data) {
        int crc = 0xFFFF;
        int polynomial = 0x1021;
        for (byte b : data.getBytes()) {
            for (int i = 0; i < 8; i++) {
                boolean bit = ((b >> (7 - i) & 1) == 1);
                boolean c15 = ((crc >> 15 & 1) == 1);
                crc <<= 1;
                if (c15 ^ bit) crc ^= polynomial;
            }
        }
        crc &= 0xffff;
        return String.format("%04X", crc).toUpperCase();
    }

    public Map<String, Object> parseTLV(String rawData) {
        return parseTLV(rawData, true);
    }

    public Map<String, Object> parseTLV(String rawData, boolean strict) {
        Map<String, Object> parsed = new java.util.LinkedHashMap<>();
        if (rawData == null || rawData.length() < 4) {
            throw new IllegalArgumentException("Invalid TLV data");
        }

        String dataForParsing;
        if (strict) {
            String dataWithoutCRC = rawData.substring(0, rawData.length() - 4);
            String providedCRC = rawData.substring(rawData.length() - 4);
            String calculatedCRC = calculateCRC(dataWithoutCRC);
            if (!providedCRC.equalsIgnoreCase(calculatedCRC)) {
                throw new IllegalArgumentException("CRC mismatch: expected " + calculatedCRC + ", but got " + providedCRC);
            }
            dataForParsing = dataWithoutCRC;
        } else {
            dataForParsing = rawData;
        }

        int i = 0;
        while (i < dataForParsing.length()) {
            if (i + 4 > dataForParsing.length()) break;
            String tag = dataForParsing.substring(i, i + 2);
            i += 2;
            try {
                int len = Integer.parseInt(dataForParsing.substring(i, i + 2));
                i += 2;
                if (i + len > dataForParsing.length()) break;
                String value = dataForParsing.substring(i, i + len);
                i += len;

                TLVTag tlvTag = tlvTags.get(tag);
                if (tlvTag != null && tlvTag.isSubTLV()) {
                    parsed.put(tag, parseSubTLV(value));
                } else {
                    parsed.put(tag, value);
                }
            } catch (NumberFormatException e) {
                break;
            }
        }
        
        if(strict) {
             parsed.put("63", rawData.substring(rawData.length() - 4));
        }
        
        return parsed;
    }

    private Map<String, String> parseSubTLV(String raw) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        int i = 0;
        while (i < raw.length()) {
            if (i + 4 > raw.length()) break;
            String tag = raw.substring(i, i + 2);
            i += 2;
            try {
                int len = Integer.parseInt(raw.substring(i, i + 2));
                i += 2;
                if (i + len > raw.length()) break;
                String val = raw.substring(i, i + len);
                i += len;
                map.put(tag, val);
            } catch (NumberFormatException e) {
                break;
            }
        }
        return map;
    }
}
