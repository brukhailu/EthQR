package com.example.EthQR.service;

import com.example.EthQR.model.QRCodeData;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QRServiceTest {

    private final QRService qrService = new QRService();

    @Test
    void testGenerateTLV() {
        QRCodeData data = new QRCodeData();
        data.setPayloadFormatIndicator("01");
        data.setPointOfInitiationMethod("11");
        data.setMerchantCategoryCode("5411");
        data.setTransactionCurrency("230");
        data.setCountryCode("ET");
        data.setMerchantName("Test Merchant");
        data.setMerchantCity("Addis Ababa");
        
        Map<String, String> merchantInfo = new HashMap<>();
        merchantInfo.put("26", "1234567890");
        data.setMerchantAccountInformation(merchantInfo);

        String tlv = qrService.generateTLV(data);
        assertNotNull(tlv);
        assertTrue(tlv.startsWith("000201010211"));
        assertTrue(tlv.contains("26101234567890"));
        assertTrue(tlv.contains("6304"));
    }

    @Test
    void testCalculateCRC() {
        String content = "000201010211261012345678905204541153032305802ET5913Test Merchant6011Addis Ababa6304";
        String crc = qrService.calculateCRC(content);
        assertEquals(4, crc.length());
    }
    
    @Test
    void testParseTLV() {
        String tlv = "000201010211261012345678905204541153032305802ET5913Test Merchant6011Addis Ababa";
        Map<String, String> result = qrService.parseTLV(tlv);
        
        assertEquals("01", result.get("00"));
        assertEquals("11", result.get("01"));
        assertEquals("1234567890", result.get("26"));
        assertEquals("5411", result.get("52"));
        assertEquals("230", result.get("53"));
        assertEquals("ET", result.get("58"));
        assertEquals("Test Merchant", result.get("59"));
        assertEquals("Addis Ababa", result.get("60"));
    }

    @Test
    void testAdditionalDataField() {
        QRCodeData data = new QRCodeData();
        data.setPayloadFormatIndicator("01");
        data.setPointOfInitiationMethod("12");
        data.setMerchantCategoryCode("5411");
        data.setTransactionCurrency("230");
        data.setCountryCode("ET");
        data.setMerchantName("Test Merchant");
        data.setMerchantCity("Addis Ababa");
        
        Map<String, String> additionalData = new TreeMap<>();
        additionalData.put("01", "BILL123");
        additionalData.put("02", "0911223344");
        data.setAdditionalDataField(additionalData);

        String tlv = qrService.generateTLV(data);
        
        // 62 (Tag) + Length + 01 (Tag) + Length + BILL123 + 02 (Tag) + Length + 0911223344
        // 0107BILL123 -> 11 chars
        // 02100911223344 -> 14 chars
        // Total length of value = 25
        // 6225...
        
        assertTrue(tlv.contains("62250107BILL12302100911223344"));
        
        Map<String, String> parsed = qrService.parseTLV(tlv);
        assertEquals("BILL123", parsed.get("62.01"));
        assertEquals("0911223344", parsed.get("62.02"));
    }

    @Test
    void testTipIndicators() {
        QRCodeData data = new QRCodeData();
        data.setPayloadFormatIndicator("01");
        data.setTipOrConvenienceIndicator("02");
        data.setValueOfConvenienceFeeFixed("10.00");
        
        String tlv = qrService.generateTLV(data);
        assertTrue(tlv.contains("550202"));
        assertTrue(tlv.contains("560510.00"));
    }
}
