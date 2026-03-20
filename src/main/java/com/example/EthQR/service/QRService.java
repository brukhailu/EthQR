package com.example.EthQR.service;

import com.example.EthQR.model.QRCodeData;
import com.example.EthQR.model.TLVTag;
import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class QRService {

    @Value("${qr.payloadFormatIndicator:01}")
    private String defaultPayloadFormatIndicator;

    @Value("${qr.transactionCurrency:230}")
    private String defaultTransactionCurrency;

    @Value("${qr.countryCode:ET}")
    private String defaultCountryCode;

    private final Map<String, TLVTag> tlvTags;

    @Autowired
    public QRService(Map<String, TLVTag> tlvTags) {
        this.tlvTags = tlvTags;
    }

    public Map<String, TLVTag> getTlvTags() {
        return tlvTags;
    }

    public String generateTLV(QRCodeData data) {
        // validate(data); // Removed old hardcoded validation in favor of config-driven validation

        Map<String, String> tlvMap = new TreeMap<>();
        
        // Tag 00: Payload Format Indicator
        TLVTag tag00Config = tlvTags.get("00");
        String payloadFormatIndicatorValue = data.getPayloadFormatIndicator() != null ? data.getPayloadFormatIndicator() : defaultPayloadFormatIndicator;
        addTLV(tlvMap, "00", processAndValidate(payloadFormatIndicatorValue, tag00Config));

        // Tag 01: Point of Initiation Method
        TLVTag tag01Config = tlvTags.get("01");
        addIfPresent(tlvMap, "01", processAndValidate(data.getPointOfInitiationMethod(), tag01Config));

        // Tags 02-51: Merchant Account Information
        if (data.getMerchantAccountInformation() != null) {
            data.getMerchantAccountInformation().forEach((tag, value) -> {
                TLVTag tlvTag = tlvTags.get(tag);
                if (tlvTag != null) {
                    addTLV(tlvMap, tag, processAndValidate(value, tlvTag));
                }
            });
        }
        
        // Check for missing mandatory Merchant Account Information if specified in config (e.g. Tag 28)
        // Note: Since MAI is dynamic, we can't easily check "all" mandatory MAI tags unless we iterate config.
        // But for Tag 28 specifically:
        TLVTag tag28Config = tlvTags.get("28");
        if(tag28Config != null && tag28Config.isMandatory()) {
             if(data.getMerchantAccountInformation() == null || !data.getMerchantAccountInformation().containsKey("28")) {
                 throw new IllegalArgumentException("Mandatory Tag 28 (EthSwitch) is missing.");
             }
        }

        // Tag 52: Merchant Category Code
        TLVTag tag52Config = tlvTags.get("52");
        addIfPresent(tlvMap, "52", processAndValidate(data.getMerchantCategoryCode(), tag52Config));

        // Tag 53: Transaction Currency
        TLVTag tag53Config = tlvTags.get("53");
        String transactionCurrencyValue = data.getTransactionCurrency() != null ? data.getTransactionCurrency() : defaultTransactionCurrency;
        addTLV(tlvMap, "53", processAndValidate(transactionCurrencyValue, tag53Config));

        // Tag 54: Transaction Amount
        TLVTag tag54Config = tlvTags.get("54");
        addIfPresent(tlvMap, "54", processAndValidate(data.getTransactionAmount(), tag54Config));

        // Tag 55: Tip or Convenience Indicator
        TLVTag tag55Config = tlvTags.get("55");
        addIfPresent(tlvMap, "55", processAndValidate(data.getTipOrConvenienceIndicator(), tag55Config));

        // Tag 56: Value of Convenience Fee Fixed
        TLVTag tag56Config = tlvTags.get("56");
        addIfPresent(tlvMap, "56", processAndValidate(data.getValueOfConvenienceFeeFixed(), tag56Config));

        // Tag 57: Value of Convenience Fee Percentage
        TLVTag tag57Config = tlvTags.get("57");
        addIfPresent(tlvMap, "57", processAndValidate(data.getValueOfConvenienceFeePercentage(), tag57Config));

        // Tag 58: Country Code
        TLVTag tag58Config = tlvTags.get("58");
        String countryCodeValue = data.getCountryCode() != null ? data.getCountryCode() : defaultCountryCode;
        addTLV(tlvMap, "58", processAndValidate(countryCodeValue, tag58Config));

        // Tag 59: Merchant Name
        TLVTag tag59Config = tlvTags.get("59");
        addIfPresent(tlvMap, "59", processAndValidate(data.getMerchantName(), tag59Config));

        // Tag 60: Merchant City
        TLVTag tag60Config = tlvTags.get("60");
        addIfPresent(tlvMap, "60", processAndValidate(data.getMerchantCity(), tag60Config));

        // Tag 61: Postal Code
        TLVTag tag61Config = tlvTags.get("61");
        addIfPresent(tlvMap, "61", processAndValidate(data.getPostalCode(), tag61Config));

        // Tag 62: Additional Data Field Template
        if (data.getAdditionalDataField() != null && !data.getAdditionalDataField().isEmpty()) {
            // We need to validate the container logic if needed, but sub-tags are validated in buildSubTLV if we pass config?
            // Actually buildSubTLV takes a map. We should validate entries first.
            TLVTag tag62Config = tlvTags.get("62");
            validateSubTags(data.getAdditionalDataField(), tag62Config);
            addTLV(tlvMap, "62", buildSubTLV(data.getAdditionalDataField()));
        } else if (tlvTags.get("62") != null && tlvTags.get("62").isMandatory()) {
             throw new IllegalArgumentException("Mandatory Tag 62 is missing.");
        }

        // Tag 64: Merchant Information - Language Template
        if (data.getMerchantInformationLanguageTemplate() != null && !data.getMerchantInformationLanguageTemplate().isEmpty()) {
            TLVTag tag64Config = tlvTags.get("64");
            validateSubTags(data.getMerchantInformationLanguageTemplate(), tag64Config);
            addTLV(tlvMap, "64", buildSubTLV(data.getMerchantInformationLanguageTemplate()));
        }

        // Tags 80-99: Unreserved Templates
        if (data.getUnreservedTemplates() != null) {
            data.getUnreservedTemplates().forEach((tag, subTags) -> {
                if (tlvTags.containsKey(tag)) {
                    TLVTag config = tlvTags.get(tag);
                    validateSubTags(subTags, config);
                    addTLV(tlvMap, tag, buildSubTLV(subTags));
                }
            });
        }

        StringBuilder tlvString = new StringBuilder();
        tlvMap.values().forEach(tlvString::append);

        if (tlvString.length() > 512) {
            throw new IllegalArgumentException("QR payload exceeds 512 characters");
        }

        String contentWithCRC = tlvString.toString() + "6304";
        String crc = calculateCRC(contentWithCRC);
        return contentWithCRC + crc;
    }

    private String processAndValidate(String value, TLVTag config) {
        if (config == null) return value;

        // Mandatory check
        if ((value == null || value.isEmpty())) {
            if (config.isMandatory()) {
                throw new IllegalArgumentException(String.format("Mandatory field '%s' (Tag %s) is missing.", config.getName(), config.getTag()));
            }
            return null; // Skip if optional and empty
        }

        validateField(value, config);

        // Sub-TLV processing (splitting by delimiter if applicable)
        if (config.isSubTLV() && config.getDelimiter() != null && value.contains(config.getDelimiter())) {
            String[] parts = value.split(config.getDelimiter());
            Map<String, String> subTags = new TreeMap<>();
            if (config.getSubTags() != null) {
                for (int i = 0; i < parts.length && i < config.getSubTags().size(); i++) {
                    String subValue = parts[i];
                    TLVTag subConfig = config.getSubTags().get(i);
                    
                    // Validate individual sub-tag value
                    if(subValue.isEmpty() && subConfig.isMandatory()) {
                         throw new IllegalArgumentException(String.format("Mandatory sub-tag '%s' (Tag %s) in '%s' is missing.", subConfig.getName(), subConfig.getTag(), config.getName()));
                    }
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

    private void validateSubTags(Map<String, String> data, TLVTag parentConfig) {
        if (parentConfig == null || parentConfig.getSubTags() == null) return;

        for (TLVTag subConfig : parentConfig.getSubTags()) {
            String val = data.get(subConfig.getTag());
            if ((val == null || val.isEmpty())) {
                if (subConfig.isMandatory()) {
                    throw new IllegalArgumentException(String.format("Mandatory sub-tag '%s' (Tag %s) in '%s' is missing.", subConfig.getName(), subConfig.getTag(), parentConfig.getName()));
                }
            } else {
                validateField(val, subConfig);
            }
        }
    }

    private void validateField(String value, TLVTag config) {
        if (value == null) return;

        // Data Type Validation
        if (config.getDataType() != null) {
            switch (config.getDataType()) {
                case "N": // Numeric
                    if (!value.matches("^\\d+$")) {
                        throw new IllegalArgumentException(String.format("Field '%s' (Tag %s) must be numeric. Found: '%s'", config.getName(), config.getTag(), value));
                    }
                    break;
                case "A": // Alphabetic
                    if (!value.matches("^[a-zA-Z\\s]+$")) {
                        throw new IllegalArgumentException(String.format("Field '%s' (Tag %s) must be alphabetic. Found: '%s'", config.getName(), config.getTag(), value));
                    }
                    break;
                case "ANS": // Alphanumeric Special
                    // Basic check, can be refined based on specific ANS char set if needed
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

    private void addIfPresent(Map<String, String> map, String tag, String value) {
        if (value != null && !value.isEmpty()) {
            addTLV(map, tag, value);
        } else {
            // Check mandatory here as well for fields that might have returned null from processAndValidate
            TLVTag config = tlvTags.get(tag);
            if(config != null && config.isMandatory()) {
                 throw new IllegalArgumentException(String.format("Mandatory field '%s' (Tag %s) is missing.", config.getName(), tag));
            }
        }
    }

    private String buildSubTLV(Map<String, String> subTags) {
        StringBuilder subTlv = new StringBuilder();
        new TreeMap<>(subTags).forEach((k, v) -> {
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
        BufferedImage image = ImageIO.read(stream);
        if (image == null) throw new IOException("Invalid image");
        
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        
        // Explicitly only look for QR Codes, try harder, and disable PURE_BARCODE mode
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.PURE_BARCODE, Boolean.FALSE);
        
        try {
            return new MultiFormatReader().decode(bitmap, hints).getText();
        } catch (NotFoundException e) {
            throw new Exception("No QR code found in the image. Please ensure the image contains a clear QR code.");
        }
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
        Map<String, Object> parsed = new LinkedHashMap<>();
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
            int crcTagIndex = dataWithoutCRC.lastIndexOf("6304");
            if(crcTagIndex == -1) {
                 // In strict parsing we expect 63 at end? 
                 // Actually spec says 63 is last.
                 // But dataWithoutCRC is "everything before last 4 chars".
                 // "6304" should be at the very end of dataWithoutCRC.
                 // The CRC value itself is the last 4 chars. The TAG+LEN "6304" precedes it.
                 // So rawData ends with "...6304XXXX".
            }
            dataForParsing = dataWithoutCRC;
        } else {
            dataForParsing = rawData;
        }

        int i = 0;
        while (i < dataForParsing.length()) {
            if (i + 4 > dataForParsing.length()) break; // Safety check
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
        Map<String, String> map = new LinkedHashMap<>();
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
