package com.example.EthQR.service;

import com.example.EthQR.model.QRCodeData;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleCRCValidationTest {

    private final QRService qrService = new QRService();

    @Test
    void testValidCRC() {
        QRCodeData data = new QRCodeData();
        data.setPayloadFormatIndicator("01");
        data.setPointOfInitiationMethod("11");
        data.setMerchantName("Test");
        
        String validTLV = qrService.generateTLV(data);
        
        // Should not throw in strict mode
        assertDoesNotThrow(() -> qrService.parseTLV(validTLV, true));
    }

    @Test
    void testInvalidCRC() {
        QRCodeData data = new QRCodeData();
        data.setPayloadFormatIndicator("01");
        data.setPointOfInitiationMethod("11");
        data.setMerchantName("Test");
        
        String validTLV = qrService.generateTLV(data);
        
        // Modify the last char to invalidate CRC
        // Ensure we don't accidentally create a valid CRC (unlikely but possible)
        // Just appending something wrong
        String invalidTLV = validTLV.substring(0, validTLV.length() - 4) + "0000";
        
        // Should throw in strict mode
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            qrService.parseTLV(invalidTLV, true);
        });
        
        assertTrue(exception.getMessage().contains("Invalid CRC"));
    }
    
    @Test
    void testSampleStringStrict() {
         String raw = "000201010211286500329668cd38095e4f9595011e1cad2b84490108SINQETAA021310583186301115204102153032305502015802ET5919KENA DIRBISA LEGESE6011Addis Ababa62620214+251-9113903200532QRfbbf0a37557b452790c81f911cd4330704725663048AEF";
         
         // Should fail due to bad CRC
         Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            qrService.parseTLV(raw, true);
        });
         
         System.out.println("Caught Expected Exception: " + exception.getMessage());
         // We expect it to be CRC related
         // Wait, depending on implementation it might check length first?
         // In my code:
         // 1. Basic length check
         // 2. CRC Validation (if strict)
         // 3. Loop
         // So it should fail on CRC first.
         
         assertTrue(exception.getMessage().contains("Invalid CRC"));
    }
}
