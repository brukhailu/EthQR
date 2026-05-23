package com.example.EthQR.model;

import lombok.Data;
import java.util.Map;

@Data
public class QRCodeData {
    private String payloadFormatIndicator; // ID 00
    private String pointOfInitiationMethod; // ID 01
    private Map<String, String> merchantAccountInformation; // ID 02-51
    private String merchantCategoryCode; // ID 52
    private String transactionCurrency; // ID 53
    private String transactionAmount; // ID 54
    private String tipOrConvenienceIndicator; // ID 55
    private String valueOfConvenienceFeeFixed; // ID 56
    private String valueOfConvenienceFeePercentage; // ID 57
    private String countryCode; // ID 58
    private String merchantName; // ID 59
    private String merchantCity; // ID 60
    private String postalCode; // ID 61
    private Map<String, String> additionalDataField; // ID 62
    private String crc; // ID 63
    private Map<String, String> merchantInformationLanguageTemplate; // ID 64
    
    private String contextOfTransaction; // ID 80
    private String discountsAndLoyalty; // ID 81
    private String offlineToOnline; // ID 82
    private String ecommerce; // ID 83
    private String uetr; // ID 84
    private Object transactionTypeCode; // ID 85 (Allows String, Number, or Map)
    private String billNumber;

    // Consumer-provided fields (captured during simulation)
    private String customerMobile;
    private String customerEmail;
    private String customerAddress;

    private Map<String, Map<String, String>> unreservedTemplates; // ID 80-99 (Legacy/Other)
}
