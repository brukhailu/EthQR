package com.example.EthQR.service;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SampleStringTest {

    @Test
    void analyzeSampleString() {
        String raw = "000201010211286500329668cd38095e4f9595011e1cad2b84490108SINQETAA021310583186301115204102153032305502015802ET5919KENA DIRBISA LEGESE6011Addis Ababa62620214+251-9113903200532QRfbbf0a37557b452790c81f911cd4330704725663048AEF";
        
        QRService service = new QRService();
        
        // Test non-strict mode - should extract what it can
        Map<String, String> parsed = service.parseTLV(raw, false);
        System.out.println("Parsed (Non-strict): " + parsed);
        
        assertTrue(parsed.containsKey("00"));
        assertTrue(parsed.containsKey("01"));
        assertTrue(parsed.containsKey("59")); // Merchant Name
        assertTrue(parsed.containsKey("60")); // Merchant City
        
        // Check if we got partial data for 62
        // Since tag 62 itself has length 62, the parser might have extracted the raw value for 62
        // but failed on sub-tags inside it.
        assertTrue(parsed.containsKey("62"));
        
        // Check for sub-tags inside 62
        // It might have parsed 02 (Mobile) successfully
        // 6262 -> 0214+251-911390320 -> OK
        // Then 0532 -> Tag 05 Len 32 -> value "QRfb..."
        // Then 0a37... -> next tags
        // So we expect 62.02 to be present
        assertTrue(parsed.containsKey("62.02"));
        
        // We might also see an error key if something went wrong inside sub-parsing
        // or if it just silently stopped.
        // The implementation catches exception in sub-parsing and puts "error_62"
        if (parsed.containsKey("error_62")) {
            System.out.println("Sub-parsing error: " + parsed.get("error_62"));
        }
    }
}
