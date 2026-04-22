package com.example.EthQR.service;

import com.example.EthQR.model.QRCodeData;
import com.example.EthQR.model.PaymentRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    @Autowired
    private TransactionLogger transactionLogger;

    @Autowired
    private Environment springEnv;

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

    @Value("${payment.instructing.agent.id:BBANKETA}")
    private String instructingAgentId;

    @Value("${payment.debtor.name:Bruk Hailu}")
    private String defaultDebtorName;

    @Value("${payment.debtor.account.id:2305130000483}")
    private String defaultDebtorAcctId;

    @Value("${payment.debtor.clearing.type:ACCT}")
    private String defaultDebtorClearingType;

    @Value("${payment.debtor.address.line:Addis Ababa}")
    private String defaultDebtorAddressLine;

    @Value("${payment.debtor.mobile:+251-922561328}")
    private String defaultDebtorMobile;

    @Value("${payment.debtor.email:customer@example.com}")
    private String defaultDebtorEmail;

    @Value("${payment.debtor.private.id:MOBN}")
    private String defaultDebtorPrivateId;

    @Value("${payment.debtor.private.id.scheme:LPNB}")
    private String defaultDebtorPrivateIdScheme;

    @Value("${payment.charge.bearer:SLEV}")
    private String chargeBearer;

    @Value("${payment.local.instrument:CRTRM}")
    private String localInstrument;

    @Value("${payment.default.currency:ETB}")
    private String defaultCurrency;

    @Value("${payment.default.category.purpose:C2BSQR}")
    private String defaultCategoryPurpose;

    @Value("${payment.default.purpose.code:ONLPUR}")
    private String defaultPurposeCode;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Helper to get environment-specific properties with fallback
    private String getProp(String env, String key, String fallback) {
        String envKey = "payment." + env + "." + key;
        String value = springEnv.getProperty(envKey);
        return (value != null) ? value : fallback;
    }

    public String getAccessToken(String transactionId, String env) throws Exception {
        String activeTokenUrl = getProp(env, "token.url", tokenUrl);
        String activeJwt = getProp(env, "jwt.assertion", jwtAssertion);
        String activeUser = getProp(env, "username", username);
        String activePass = getProp(env, "password", password);

        transactionLogger.log(transactionId, "--- 1. Requesting Access Token [" + env + "] ---");
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(activeTokenUrl);

            httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
            httpPost.setHeader("jwt-assertion", activeJwt);
            httpPost.setHeader(HttpHeaders.USER_AGENT, "Apache-HttpClient/4.5.14 (Java/21.0.9)");

            String body = "grant_type=password&username=" + URLEncoder.encode(activeUser, StandardCharsets.UTF_8) +
                    "&password=" + URLEncoder.encode(activePass, StandardCharsets.UTF_8);
            httpPost.setEntity(new StringEntity(body));

            transactionLogger.log(transactionId, "Token Request URL: " + activeTokenUrl);
            transactionLogger.log(transactionId, "Token Request Body: " + body);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getStatusLine().getStatusCode();
                HttpEntity entity = response.getEntity();
                String responseString = entity != null ? EntityUtils.toString(entity) : "";

                transactionLogger.log(transactionId, "Token Response Status: " + statusCode);
                transactionLogger.log(transactionId, "Token Response Body: " + responseString);

                if (statusCode >= 200 && statusCode < 300) {
                    JsonNode jsonNode = objectMapper.readTree(responseString);
                    if (jsonNode.has("access_token")) {
                        String token = jsonNode.get("access_token").asText();
                        transactionLogger.log(transactionId, "Access Token Acquired Successfully.");
                        return token;
                    } else {
                        throw new IOException("Access token not found in response.");
                    }
                } else {
                    throw new IOException("Failed to get token. Status: " + statusCode + ", Detail: " + responseString);
                }
            }
        }
    }

    public Map<String, String> processPayment(QRCodeData qrData, PaymentRequest userInput, String transactionId, String env) throws Exception {
        String activeIncomingUrl = getProp(env, "incoming.url", incomingUrl);
        String activeBic = getProp(env, "bic", instructingAgentId);

        String endToEndId = generateEndToEndId(activeBic);
        String txId = generateTransactionId(activeBic);
        String uetr = qrData.getUetr() != null ? qrData.getUetr() : UUID.randomUUID().toString();

        // Determine the single effective tip amount to use for all calculations and XML
        BigDecimal effectiveTipAmount = determineEffectiveTipAmount(qrData, userInput);
        transactionLogger.log(transactionId, "Effective Tip Amount: " + effectiveTipAmount);

        // Calculate BASE amount (without tip) and TOTAL amount (for display only)
        BigDecimal baseAmount = getBaseAmount(qrData, userInput);
        BigDecimal totalAmount = baseAmount.add(effectiveTipAmount);  // Only for display/success screen

        transactionLogger.log(transactionId, "Base Amount: " + baseAmount);
        transactionLogger.log(transactionId, "Tip Amount: " + effectiveTipAmount);
        transactionLogger.log(transactionId, "Total Amount (for display): " + totalAmount);

        String currency = qrData.getTransactionCurrency() != null ?
                getCurrencyCode(qrData.getTransactionCurrency()) : defaultCurrency;

        // Build message - passing base amount (without tip) and tip amount separately
        Pacs008Message message = buildPacs008Message(qrData, userInput, transactionId,
                endToEndId, txId, uetr, baseAmount, effectiveTipAmount, currency, activeBic);
        String pacs008 = message.toXml();


        String signedPacs008 = getDigestedMessage(pacs008, transactionId);
        String accessToken = getAccessToken(transactionId, env);
        String responseXml = sendPaymentRequest(signedPacs008, accessToken, transactionId, activeIncomingUrl);

        Map<String, String> result = new HashMap<>();
        result.put("endToEndId", endToEndId);
        result.put("txId", txId);
        result.put("uetr", uetr);
        result.put("totalAmount", totalAmount.toString());
        result.put("currency", currency);
        result.put("response", responseXml);
        return result;
    }

    private BigDecimal getBaseAmount(QRCodeData qrData, PaymentRequest userInput) {
        // Get base amount from QR or user input (this is the amount without tip)
        if (qrData.getTransactionAmount() != null && !qrData.getTransactionAmount().isEmpty()) {
            return new BigDecimal(qrData.getTransactionAmount());
        } else if (userInput.getAmount() != null) {
            return userInput.getAmount();
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal determineEffectiveTipAmount(QRCodeData qrData, PaymentRequest userInput) {
        // Priority 1: User-entered tip amount (always use this if provided, covers prompted case)
        if (userInput.getTipAmount() != null) {
            return userInput.getTipAmount();
        }

        // Priority 2: QR logic
        String tipIndicator = qrData.getTipOrConvenienceIndicator();
        if (tipIndicator != null) {
            switch (tipIndicator) {
                case "02": // Fixed tip amount
                    if (qrData.getValueOfConvenienceFeeFixed() != null && !qrData.getValueOfConvenienceFeeFixed().isEmpty()) {
                        return new BigDecimal(qrData.getValueOfConvenienceFeeFixed());
                    }
                    break;
                case "03": // Percentage tip
                    BigDecimal baseAmount = BigDecimal.ZERO;
                    if (qrData.getTransactionAmount() != null && !qrData.getTransactionAmount().isEmpty()) {
                        baseAmount = new BigDecimal(qrData.getTransactionAmount());
                    } else if (userInput.getAmount() != null) {
                        baseAmount = userInput.getAmount();
                    }
                    if (qrData.getValueOfConvenienceFeePercentage() != null && !qrData.getValueOfConvenienceFeePercentage().isEmpty()) {
                        BigDecimal percentage = new BigDecimal(qrData.getValueOfConvenienceFeePercentage());
                        return baseAmount.multiply(percentage).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
                    }
                    break;
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal calculateTotalAmount(QRCodeData qrData, PaymentRequest userInput, BigDecimal effectiveTipAmount) {
        BigDecimal baseAmount = BigDecimal.ZERO;

        // Get base amount from QR or user input
        if (qrData.getTransactionAmount() != null && !qrData.getTransactionAmount().isEmpty()) {
            baseAmount = new BigDecimal(qrData.getTransactionAmount());
        } else if (userInput.getAmount() != null) {
            baseAmount = userInput.getAmount();
        }

        return baseAmount.add(effectiveTipAmount);
    }

    private String getCurrencyCode(String numericCurrency) {
        // Map numeric currency codes to ISO 4217 alpha codes
        Map<String, String> currencyMap = new HashMap<>();
        currencyMap.put("230", "ETB");
        currencyMap.put("840", "USD");
        currencyMap.put("826", "GBP");
        currencyMap.put("978", "EUR");
        // Add more as needed
        return currencyMap.getOrDefault(numericCurrency, defaultCurrency);
    }

    private Pacs008Message buildPacs008Message(QRCodeData qrData, PaymentRequest userInput,
                                               String transactionId, String endToEndId,
                                               String txId, String uetr,
                                               BigDecimal baseAmountArg, BigDecimal effectiveTipAmount, String currency, String activeBic) {
        transactionLogger.log(transactionId, "--- 2. Building pacs.008 Message from QR Data ---");

        DateTimeFormatter offsetFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX");
        String createDtTm = LocalDateTime.now().atZone(ZoneId.systemDefault()).format(offsetFormatter);
        String createDt = createDtTm;

        DateTimeFormatter utcFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'");
        String signingTime = LocalDateTime.now(ZoneOffset.UTC).format(utcFormatter);


        // Determine category purpose and purpose code
        String categoryPurpose = determineCategoryPurpose(qrData, userInput);
        String purposeCode = determinePurposeCode(qrData, userInput);
        // Parse Merchant Account Information (tag 28)
        String instructedAgentId = "ETSTETA";
        String creditorAcctId = "000000000000";
        String clearingType = "ACCT";
        String guid = null;
        Map<String, String> mai = qrData.getMerchantAccountInformation();
        if (mai != null && mai.containsKey("28")) {
            String value = mai.get("28");
            transactionLogger.log(transactionId, "Tag 28 raw value: " + value);
            String[] parts = value.split("\\|");

            // Index 0: Globally Unique Identifier (GUID) - Store for reference but not used in pacs.008
            if (parts.length >= 1 && parts[0] != null && !parts[0].isEmpty()) {
                guid = parts[0];
                transactionLogger.log(transactionId, "GUID: " + guid);
            }

            // Index 1: MSP BIC or Participant ID → Instructed Agent (Creditor's Bank BIC)
            if (parts.length >= 2 && parts[1] != null && !parts[1].isEmpty()) {
                instructedAgentId = parts[1];
                transactionLogger.log(transactionId, "Instructed Agent BIC: " + instructedAgentId);
            }

            // Index 2: Account Number → Creditor Account
            if (parts.length >= 3 && parts[2] != null && !parts[2].isEmpty()) {
                creditorAcctId = parts[2];
                transactionLogger.log(transactionId, "Creditor Account Number: " + creditorAcctId);
            }

            // Index 3: Clearing Type (optional)
            if (parts.length >= 4 && parts[3] != null && !parts[3].isEmpty()) {
                clearingType = parts[3];
            }
        }

// Store GUID if needed elsewhere (e.g., in remittance info)
        if (guid != null) {
            // You can use GUID in remittance or as reference
            transactionLogger.log(transactionId, "QR Transaction GUID: " + guid);
        }

        // Build remittance information
        String remittanceUnstructured = buildRemittanceUnstructured(qrData, userInput, effectiveTipAmount);
        String billNumber = extractBillNumber(qrData, userInput);
        String mobileNumber = extractMobileNumber(qrData, userInput);
        String storeLabel = extractStoreLabel(qrData);
        String loyaltyNumber = extractLoyaltyNumber(qrData);
        String terminalId = extractTerminalId(qrData);

        // Check for additional consumer data request (tag 62 sub 09)
        String additionalConsumerData = extractAdditionalConsumerDataRequest(qrData);
        boolean requestAddress = additionalConsumerData != null && additionalConsumerData.contains("A");
        boolean requestAddressStreet = additionalConsumerData != null && additionalConsumerData.contains("S");
        boolean requestAddressBuilding = additionalConsumerData != null && additionalConsumerData.contains("B");
        boolean requestAddressTown = additionalConsumerData != null && additionalConsumerData.contains("T");
        boolean requestAddressCountry = additionalConsumerData != null && additionalConsumerData.contains("C");
        boolean requestEmail = additionalConsumerData != null && additionalConsumerData.contains("E");
        boolean requestMobile = additionalConsumerData != null && additionalConsumerData.contains("M");

        // Build consumer data from user input based on request
        String consumerAddress = (requestAddress && userInput.getConsumerAddress() != null) ?
                userInput.getConsumerAddress() : null;
        String consumerEmail = (requestEmail && userInput.getConsumerEmail() != null) ?
                userInput.getConsumerEmail() : null;
        String consumerMobile = (requestMobile && userInput.getConsumerMobile() != null) ?
                userInput.getConsumerMobile() : null;

        // Build debtor info (customer info)
        // Build debtor info (customer info) - UPDATED to use all fields
// Build debtor info (customer info) - PRIORITIZE PROMPTED VALUES
        String debtorName = userInput.getDebtorName() != null ?
                userInput.getDebtorName() :
                (userInput.getCustomerName() != null ? userInput.getCustomerName() : defaultDebtorName);

// Priority for Address: Prompted consumer address > user input > default
        String debtorAddress = consumerAddress != null ? consumerAddress :
                (userInput.getDebtorAddress() != null ? userInput.getDebtorAddress() :
                        (userInput.getConsumerAddress() != null ? userInput.getConsumerAddress() :
                                (userInput.getCustomerAddress() != null ? userInput.getCustomerAddress() : defaultDebtorAddressLine)));

// Priority for Mobile: Prompted consumer mobile > user input > default
        String debtorMobile = consumerMobile != null ? consumerMobile :
                (userInput.getDebtorMobile() != null ? userInput.getDebtorMobile() :
                        (userInput.getConsumerMobile() != null ? userInput.getConsumerMobile() :
                                (userInput.getCustomerMobile() != null ? userInput.getCustomerMobile() : defaultDebtorMobile)));

// Priority for Email: Prompted consumer email > user input > default
        String debtorEmail = consumerEmail != null ? consumerEmail :
                (userInput.getDebtorEmail() != null ? userInput.getDebtorEmail() :
                        (userInput.getConsumerEmail() != null ? userInput.getConsumerEmail() :
                                (userInput.getCustomerEmail() != null ? userInput.getCustomerEmail() : defaultDebtorEmail)));

        String debtorAcctId = userInput.getDebtorAccountId() != null ?
                userInput.getDebtorAccountId() :
                (userInput.getCustomerAccountId() != null ? userInput.getCustomerAccountId() : defaultDebtorAcctId);
        // Build creditor info (merchant info)
        String creditorName = qrData.getMerchantName() != null ? qrData.getMerchantName() : "Merchant";
        String creditorTownName = qrData.getMerchantCity() != null ? qrData.getMerchantCity() : "";
        String merchantTaxId = extractMerchantTaxId(qrData);
        String merchantChannel = extractMerchantChannel(qrData);
        String merchantMobile = extractMerchantMobile(qrData);


// ===== ADD THESE MISSING EXTRACTIONS =====
// Tag 52: Merchant Category Code (MCC) - for Creditor Organization ID
        String merchantCategoryCode = qrData.getMerchantCategoryCode();
        transactionLogger.log(transactionId, "Merchant Category Code (MCC): " + merchantCategoryCode);

// Tag 58: Country Code - for Creditor Country and CountryOfRes
        String countryCode = qrData.getCountryCode() != null ? qrData.getCountryCode() : "ET";

// Tag 61: Postal Code
        String postalCode = qrData.getPostalCode();
        transactionLogger.log(transactionId, "Postal Code: " + postalCode);

// Tag 84: UETR from QR (already used, but ensure it's logged)
        if (qrData.getUetr() != null) {
            transactionLogger.log(transactionId, "UETR from QR: " + qrData.getUetr());
        }

// Address components from additional data (if available)
        String creditorStreetName = null;
        String creditorBuildingNumber = null;
        String creditorAddressLine = null;
        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null) {
            if (additionalData.containsKey("14")) creditorStreetName = additionalData.get("14");
            if (additionalData.containsKey("15")) creditorBuildingNumber = additionalData.get("15");
            if (additionalData.containsKey("16")) creditorAddressLine = additionalData.get("16");
        }
// ===== END OF ADDITIONS =====

        // Determine if this is a bill payment
        boolean isBillPayment = "C2BBPT".equals(categoryPurpose) || billNumber != null;

        // Extract ultimate creditor (could be biller)
        String ultimateCreditorId = billNumber;

        // Generate unique IDs
        String bizMsgId = activeBic + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String msgId = activeBic + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        // Total amount for message
        BigDecimal totalAmount = baseAmountArg.add(effectiveTipAmount);

        return new Pacs008Message()
                .withBizMsgId(bizMsgId)
                .withMsgId(msgId)
                .withCreateDt(createDt)
                .withCreateDtTm(createDtTm)
                .withSigningTime(signingTime)
                .withInstructingAgentId(activeBic)
                .withInstructedAgentId(instructedAgentId)
                .withEndToEndId(endToEndId)
                .withTxId(txId)
                .withUetr(uetr)
                .withCurrency(currency)
                .withAmount(totalAmount.toString())
                .withTipAmount(effectiveTipAmount.compareTo(BigDecimal.ZERO) > 0 ? effectiveTipAmount.toString() : null)
                .withChargeBearer(chargeBearer)
                .withLocalInstrument(localInstrument)
                .withCategoryPurpose(categoryPurpose)
                .withPurposeCode(purposeCode)
                // Debtor info (Customer) - USE PROMPTED VALUES FIRST
                .withDebtorName(debtorName)
                .withDebtorAddressLine(
                        consumerAddress != null ? consumerAddress : debtorAddress
                )
                .withDebtorMobile(
                        consumerMobile != null ? consumerMobile : debtorMobile
                )
                .withDebtorEmail(
                        consumerEmail != null ? consumerEmail : debtorEmail
                )
                .withDebtorAcctId(debtorAcctId)
                .withDebtorClearingType(defaultDebtorClearingType)
                .withDebtorPrivateId("LPNB")
                .withDebtorPrivateIdScheme(loyaltyNumber != null ? loyaltyNumber : null)
                // Creditor info (Merchant)
                .withCreditorName(creditorName)
                .withCreditorStreetName(creditorStreetName)
                .withCreditorBuildingNumber(creditorBuildingNumber)
                .withCreditorTownName(creditorTownName)
                .withCreditorCountry(countryCode)
                .withCreditorAddressLine(creditorAddressLine)
                .withCreditorOrgId(merchantCategoryCode)
                .withCreditorCountryOfRes(countryCode)
                .withCreditorContactChannel(merchantChannel != null ? merchantChannel : "QRCP")  // Uses "QRCP" as fallback
                .withCreditorMobile(merchantMobile)
                .withCreditorAcctId(creditorAcctId)
                .withCreditorClearingType(clearingType)
                // Optional fields
                .withUltimateCreditorId(ultimateCreditorId)
                .withMerchantTaxId(merchantTaxId)
                .withInstructionForNextAgent(instructedAgentId)  // Always include
                .withRemittanceUnstructured(remittanceUnstructured)
                .withBillNumber(billNumber)
                .withMobileNumber(mobileNumber)  // QR Tag 02 for merchant mobile
                .withStoreLabel(storeLabel)
                .withLoyaltyNumber(loyaltyNumber)
                .withTerminalId(terminalId)
                .withMerchantChannel(merchantChannel);  // Add this line
    }

    private String determineCategoryPurpose(QRCodeData qrData, PaymentRequest userInput) {
        // Priority: User input > QR data > Default

        if (userInput.getCategoryPurpose() != null && !userInput.getCategoryPurpose().isEmpty()) {
            return userInput.getCategoryPurpose();
        }

        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null && additionalData.containsKey("08")) {
            String purpose = additionalData.get("08");
            if (purpose != null) {
                if (purpose.toLowerCase().contains("bill")) return "C2BSQR";
                if (purpose.toLowerCase().contains("merchant")) return "C2BSQR";
            }
        }

        String mcc = qrData.getMerchantCategoryCode();
        if (mcc != null) {
            // Bill payment MCCs (utilities, telecom, etc.)
            if (mcc.startsWith("48") || mcc.startsWith("49") || mcc.equals("9399")) {
                return "C2BSQR";
            }
        }

        if (qrData.getBillNumber() != null) {
            return "C2BSQR";
        }

        return defaultCategoryPurpose;
    }

    private String determinePurposeCode(QRCodeData qrData, PaymentRequest userInput) {
        if (userInput.getPurposeCode() != null && !userInput.getPurposeCode().isEmpty()) {
            return userInput.getPurposeCode();
        }

        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null && additionalData.containsKey("08")) {
            String purpose = additionalData.get("08");
            if (purpose != null) {
                String p = purpose.toLowerCase();
                if (p.contains("online")) return "ONLPUR";
                if (p.contains("salary")) return "SALA";
                if (p.contains("government")) return "GOVTP";
                if (p.contains("airline") || p.contains("ticket")) return "AIRTK";
                if (p.contains("bar") || p.contains("club")) return "BCLUB";
                if (p.contains("bus")) return "BUSTP";
                if (p.contains("school") || p.contains("college") || p.contains("university") || p.contains("education"))
                    return "EDUPT";
                if (p.contains("entertainment") || p.contains("recreation")) return "ENTMT";
                if (p.contains("forex")) return "FOREX";
                if (p.contains("gambling") || p.contains("betting") || p.contains("lottery") || p.contains("casino"))
                    return "GAMBL";
                if (p.contains("gift") || p.contains("souvenir") || p.contains("novelty")) return "GIFTS";
                if (p.contains("grocery") || p.contains("supermarket")) return "GROCS";
                if (p.contains("spa") || p.contains("beauty")) return "HLTHS";
                if (p.contains("hospital")) return "HOSPT";
                if (p.contains("loan")) return "LOANP";
                if (p.contains("pet")) return "PETSP";
                if (p.contains("pharmacy") || p.contains("medicine")) return "PHARM";
                if (p.contains("restaurant") || p.contains("cafe") || p.contains("food")) return "RETNTP";
                if (p.contains("ride") || p.contains("taxi") || p.contains("uber")) return "RIDES";
                if (p.contains("stationery") || p.contains("office")) return "STOFS";
                if (p.contains("train") || p.contains("subway")) return "TRNST";
                if (p.contains("utility") || p.contains("bill") || p.contains("electric") || p.contains("water"))
                    return "UTSBP";
            }
        }

        return defaultPurposeCode;
    }

    private String buildRemittanceUnstructured(QRCodeData qrData, PaymentRequest userInput, BigDecimal effectiveTipAmount) {
        StringBuilder sb = new StringBuilder();

        // Prepend "Tip: " if there's an effective tip amount
        if (effectiveTipAmount != null && effectiveTipAmount.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("Tip: ");
        }

        String paymentReason = null;
        Map<String, String> additionalData = qrData.getAdditionalDataField();

        // Priority 1: User input from simulator
        if (userInput.getRemittanceInfo() != null && !userInput.getRemittanceInfo().isEmpty()) {
            paymentReason = userInput.getRemittanceInfo();
        }
        // Priority 2: Tag 80 from QR (ContextOfTransaction)
        else if (qrData.getContextOfTransaction() != null && !qrData.getContextOfTransaction().isEmpty()) {
            paymentReason = qrData.getContextOfTransaction();
        }
        // Priority 3: Tag 08 from Additional Data Field
        else if (additionalData != null && additionalData.containsKey("08") && !additionalData.get("08").isEmpty()) {
            paymentReason = additionalData.get("08");
        }
        // Priority 4: Tag 05 from Additional Data Field
        else if (additionalData != null && additionalData.containsKey("05") && !additionalData.get("05").isEmpty()) {
            paymentReason = additionalData.get("05");
        }

        if (paymentReason != null) {
            sb.append(paymentReason);
        }

        // Final fallback if no specific reason is found (and only "Tip: " is present or empty)
        String current = sb.toString();
        if (current.isEmpty() || current.equals("Tip: ")) { // Check if it's empty or just "Tip: "
            sb.append("Payment");
        }

        return sb.toString();
    }

    private String extractBillNumber(QRCodeData qrData, PaymentRequest userInput) {
        if (userInput.getBillNumber() != null && !userInput.getBillNumber().isEmpty()) {
            return userInput.getBillNumber();
        }

        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null && additionalData.containsKey("01")) {
            return additionalData.get("01");
        }

        return null;
    }

    private String extractMobileNumber(QRCodeData qrData, PaymentRequest userInput) {
        if (userInput.getMobileNumber() != null && !userInput.getMobileNumber().isEmpty()) {
            return userInput.getMobileNumber();
        }

        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null && additionalData.containsKey("02")) {
            return additionalData.get("02");
        }

        return null;
    }

    private String extractStoreLabel(QRCodeData qrData) {
        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null && additionalData.containsKey("03")) {
            return additionalData.get("03");
        }
        return null;
    }

    private String extractLoyaltyNumber(QRCodeData qrData) {
        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null && additionalData.containsKey("04")) {
            return additionalData.get("04");
        }
        return null;
    }

    private String extractTerminalId(QRCodeData qrData) {
        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null && additionalData.containsKey("07")) {
            return additionalData.get("07");
        }
        return null;
    }

    private String extractAdditionalConsumerDataRequest(QRCodeData qrData) {
        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null && additionalData.containsKey("09")) {
            return additionalData.get("09");
        }
        return null;
    }

    private String extractMerchantTaxId(QRCodeData qrData) {
        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null && additionalData.containsKey("10")) {
            return additionalData.get("10");
        }
        return null;
    }

    private String extractMerchantChannel(QRCodeData qrData) {
        // Tag 61/62 - Additional Data Field (where sub-tag 11 is located)
        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null && additionalData.containsKey("11")) {
            String channel = additionalData.get("11");
            return channel;
        }
        return null;
    }

    private String extractMerchantMobile(QRCodeData qrData) {
        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null && additionalData.containsKey("02")) {
            return additionalData.get("02");
        }
        return null;
    }

    private String generateEndToEndId(String bic) {
        return bic + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String generateTransactionId(String bic) {
        return bic + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String getDigestedMessage(String xmlMessage, String transactionId) throws Exception {
        transactionLogger.log(transactionId, "--- 3. Requesting Message Digest ---");
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(digestUrl);
            httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/xml");
            httpPost.setHeader(HttpHeaders.USER_AGENT, "Apache-HttpClient/4.5.14 (Java/21.0.9)");
            httpPost.setEntity(new StringEntity(xmlMessage, StandardCharsets.UTF_8));

            transactionLogger.log(transactionId, "Digest Request URL: " + digestUrl);
            transactionLogger.log(transactionId, "Digest Request Body:\n" + xmlMessage);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getStatusLine().getStatusCode();
                HttpEntity entity = response.getEntity();
                String responseString = entity != null ? EntityUtils.toString(entity) : "";

                transactionLogger.log(transactionId, "Digest Response Status: " + statusCode);
                transactionLogger.log(transactionId, "Digest Response Body:\n" + responseString);

                if (statusCode >= 200 && statusCode < 300) {
                    transactionLogger.log(transactionId, "Message Digested Successfully.");
                    return responseString;
                } else {
                    throw new IOException("Failed to digest message. Status: " + statusCode + ", Detail: " + responseString);
                }
            }
        }
    }

    private String sendPaymentRequest(String signedXml, String accessToken, String transactionId, String activeIncomingUrl) throws Exception {
        transactionLogger.log(transactionId, "--- 4. Sending Final Payment Request ---");
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(activeIncomingUrl);
            httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/xml");
            httpPost.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            httpPost.setEntity(new StringEntity(signedXml, StandardCharsets.UTF_8));

            transactionLogger.log(transactionId, "Payment Request URL: " + activeIncomingUrl);
            transactionLogger.log(transactionId, "Payment Request Body:\n" + signedXml);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getStatusLine().getStatusCode();
                HttpEntity entity = response.getEntity();
                String responseString = entity != null ? EntityUtils.toString(entity) : "";

                transactionLogger.log(transactionId, "Payment Response Status: " + statusCode);
                transactionLogger.log(transactionId, "Payment Response Body: " + responseString);

                if (statusCode >= 200 && statusCode < 300) {
                    Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(responseString)));
                    XPath xPath = XPathFactory.newInstance().newXPath();
                    String txStatus = xPath.compile("//*[local-name()='TxSts']/text()").evaluate(doc);

                    if ("ACSC".equals(txStatus)) {
                        transactionLogger.log(transactionId, "Payment Confirmed with Status: ACSC");
                        return responseString;
                    } else {
                        String reason = xPath.compile("//*[local-name()='Rsn']//*[local-name()='Prtry']/text()").evaluate(doc);
                        if (reason == null || reason.isEmpty()) {
                            reason = xPath.compile("//*[local-name()='AddtlInf']/text()").evaluate(doc);
                        }
                        if (reason == null || reason.isEmpty()) {
                            reason = "Transaction rejected with status: " + txStatus;
                        }
                        throw new IOException(reason);
                    }
                } else {
                    throw new IOException("Payment Failed. Status: " + statusCode + ", Detail: " + responseString);
                }
            }
        }
    }

    private String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Builder class for pacs.008 message with all optional fields
     */
    private class Pacs008Message {
        private String bizMsgId;
        private String msgId;
        private String createDt;
        private String createDtTm;
        private String signingTime;
        private String instructingAgentId;
        private String instructedAgentId;
        private String endToEndId;
        private String txId;
        private String uetr;
        private String currency;
        private String amount;
        private String chargeBearer;
        private String localInstrument;
        private String categoryPurpose;
        private String purposeCode;
        private String tipAmount;

        // Debtor fields (Customer)
        private String debtorName;
        private String debtorAddressLine;
        private String debtorMobile;
        private String debtorEmail;
        private String debtorPrivateId;
        private String debtorPrivateIdScheme;
        private String debtorAcctId;
        private String debtorClearingType;

        // Creditor fields (Merchant) - Updated with all optional fields
        private String creditorName;
        private String creditorStreetName;
        private String creditorBuildingNumber;
        private String creditorPostalCode;
        private String creditorTownName;
        private String creditorCountry;
        private String creditorAddressLine;
        private String creditorOrgId;
        private String creditorCountryOfRes;
        private String creditorContactChannel;
        private String creditorMobile;
        private String creditorAcctId;
        private String creditorClearingType;

        // Optional fields
        private String ultimateCreditorId;
        private String merchantTaxId;
        private String instructionForNextAgent;
        private String remittanceUnstructured;
        private String billNumber;
        private String mobileNumber;
        private String storeLabel;
        private String loyaltyNumber;
        private String terminalId;
        private String merchantChannel;


        // Builder methods
        public Pacs008Message withBizMsgId(String id) {
            this.bizMsgId = id;
            return this;
        }

        public Pacs008Message withMsgId(String id) {
            this.msgId = id;
            return this;
        }

        public Pacs008Message withCreateDt(String dt) {
            this.createDt = dt;
            return this;
        }

        public Pacs008Message withCreateDtTm(String dt) {
            this.createDtTm = dt;
            return this;
        }

        public Pacs008Message withSigningTime(String time) {
            this.signingTime = time;
            return this;
        }

        public Pacs008Message withInstructingAgentId(String id) {
            this.instructingAgentId = id;
            return this;
        }

        public Pacs008Message withInstructedAgentId(String id) {
            this.instructedAgentId = id;
            return this;
        }

        public Pacs008Message withEndToEndId(String id) {
            this.endToEndId = id;
            return this;
        }

        public Pacs008Message withTxId(String id) {
            this.txId = id;
            return this;
        }

        public Pacs008Message withUetr(String uetr) {
            this.uetr = uetr;
            return this;
        }

        public Pacs008Message withCurrency(String currency) {
            this.currency = currency;
            return this;
        }

        public Pacs008Message withAmount(String amount) {
            this.amount = amount;
            return this;
        }

        public Pacs008Message withTipAmount(String tipAmount) {
            this.tipAmount = tipAmount;
            return this;
        }

        public Pacs008Message withChargeBearer(String bearer) {
            this.chargeBearer = bearer;
            return this;
        }

        public Pacs008Message withLocalInstrument(String instrument) {
            this.localInstrument = instrument;
            return this;
        }

        public Pacs008Message withCategoryPurpose(String purpose) {
            this.categoryPurpose = purpose;
            return this;
        }

        public Pacs008Message withPurposeCode(String code) {
            this.purposeCode = code;
            return this;
        }

        public Pacs008Message withDebtorName(String name) {
            this.debtorName = name;
            return this;
        }

        public Pacs008Message withDebtorAddressLine(String address) {
            this.debtorAddressLine = address;
            return this;
        }

        public Pacs008Message withDebtorMobile(String mobile) {
            this.debtorMobile = mobile;
            return this;
        }

        public Pacs008Message withDebtorEmail(String email) {
            this.debtorEmail = email;
            return this;
        }

        public Pacs008Message withDebtorPrivateId(String id) {
            this.debtorPrivateId = id;
            return this;
        }

        public Pacs008Message withDebtorPrivateIdScheme(String scheme) {
            this.debtorPrivateIdScheme = scheme;
            return this;
        }

        public Pacs008Message withDebtorAcctId(String id) {
            this.debtorAcctId = id;
            return this;
        }

        public Pacs008Message withDebtorClearingType(String type) {
            this.debtorClearingType = type;
            return this;
        }

        // Creditor builder methods - Updated
        public Pacs008Message withCreditorName(String name) {
            this.creditorName = name;
            return this;
        }

        public Pacs008Message withCreditorStreetName(String streetName) {
            this.creditorStreetName = streetName;
            return this;
        }

        public Pacs008Message withCreditorBuildingNumber(String buildingNumber) {
            this.creditorBuildingNumber = buildingNumber;
            return this;
        }

        public Pacs008Message withCreditorPostalCode(String postalCode) {
            this.creditorPostalCode = postalCode;
            return this;
        }

        public Pacs008Message withCreditorTownName(String town) {
            this.creditorTownName = town;
            return this;
        }

        public Pacs008Message withCreditorCountry(String country) {
            this.creditorCountry = country;
            return this;
        }

        public Pacs008Message withCreditorAddressLine(String addressLine) {
            this.creditorAddressLine = addressLine;
            return this;
        }

        public Pacs008Message withCreditorOrgId(String orgId) {
            this.creditorOrgId = orgId;
            return this;
        }

        public Pacs008Message withCreditorCountryOfRes(String country) {
            this.creditorCountryOfRes = country;
            return this;
        }

        public Pacs008Message withCreditorContactChannel(String channel) {
            this.creditorContactChannel = channel;
            return this;
        }

        public Pacs008Message withCreditorMobile(String mobile) {
            this.creditorMobile = mobile;
            return this;
        }

        public Pacs008Message withCreditorAcctId(String id) {
            this.creditorAcctId = id;
            return this;
        }

        public Pacs008Message withCreditorClearingType(String type) {
            this.creditorClearingType = type;
            return this;
        }

        public Pacs008Message withUltimateCreditorId(String id) {
            this.ultimateCreditorId = id;
            return this;
        }

        public Pacs008Message withMerchantTaxId(String id) {
            this.merchantTaxId = id;
            return this;
        }

        public Pacs008Message withInstructionForNextAgent(String agent) {
            this.instructionForNextAgent = agent;
            return this;
        }

        public Pacs008Message withRemittanceUnstructured(String remittance) {
            this.remittanceUnstructured = remittance;
            return this;
        }

        public Pacs008Message withBillNumber(String number) {
            this.billNumber = number;
            return this;
        }

        public Pacs008Message withMobileNumber(String number) {
            this.mobileNumber = number;
            return this;
        }

        public Pacs008Message withStoreLabel(String label) {
            this.storeLabel = label;
            return this;
        }

        public Pacs008Message withLoyaltyNumber(String number) {
            this.loyaltyNumber = number;
            return this;
        }

        public Pacs008Message withTerminalId(String id) {
            this.terminalId = id;
            return this;
        }

        public Pacs008Message withMerchantChannel(String channel) {
            this.merchantChannel = channel;
            return this;
        }

        private String buildRemittanceString() {
            StringBuilder sb = new StringBuilder();

            // Add remittance info from user input
            if (remittanceUnstructured != null && !remittanceUnstructured.isEmpty()) {
                sb.append(remittanceUnstructured);
            }

            String result = sb.toString().trim();
            return result.isEmpty() ? "Payment" : result;
        }

        public String toXml() {
            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<FPEnvelope xmlns=\"urn:iso:std:iso:20022:tech:xsd:payment_request\" ");
            xml.append("xmlns:document=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.10\" ");
            xml.append("xmlns:header=\"urn:iso:std:iso:20022:tech:xsd:head.001.001.03\">\n");
            xml.append("    <header:AppHdr>\n");
            xml.append("        <header:Fr>\n");
            xml.append("            <header:FIId>\n");
            xml.append("                <header:FinInstnId>\n");
            xml.append("                    <header:Othr>\n");
            xml.append("                        <header:Id>").append(escapeXml(instructingAgentId)).append("</header:Id>\n");
            xml.append("                    </header:Othr>\n");
            xml.append("                </header:FinInstnId>\n");
            xml.append("        </header:Fr>\n");
            xml.append("        <header:To>\n");
            xml.append("            <header:FIId>\n");
            xml.append("                <header:FinInstnId>\n");
            xml.append("                    <header:Othr>\n");
            xml.append("                        <header:Id>FP</header:Id>\n");
            xml.append("                    </header:Othr>\n");
            xml.append("                </header:FinInstnId>\n");
            xml.append("            </header:FIId>\n");
            xml.append("        </header:To>\n");
            xml.append("        <header:BizMsgIdr>").append(escapeXml(bizMsgId)).append("</header:BizMsgIdr>\n");
            xml.append("        <header:MsgDefIdr>pacs.008.001.10</header:MsgDefIdr>\n");
            xml.append("        <header:CreDt>").append(escapeXml(createDt)).append("</header:CreDt>\n");
            xml.append("    </header:AppHdr>\n");
            xml.append("    <document:Document>\n");
            xml.append("        <document:FIToFICstmrCdtTrf>\n");
            xml.append("            <document:GrpHdr>\n");
            xml.append("                <document:MsgId>").append(escapeXml(msgId)).append("</document:MsgId>\n");
            xml.append("                <document:CreDtTm>").append(escapeXml(createDtTm)).append("</document:CreDtTm>\n");
            xml.append("                <document:NbOfTxs>1</document:NbOfTxs>\n");
            xml.append("                <document:SttlmInf>\n");
            xml.append("                    <document:SttlmMtd>CLRG</document:SttlmMtd>\n");
            xml.append("                    <document:ClrSys>\n");
            xml.append("                        <document:Prtry>FP</document:Prtry>\n");
            xml.append("                    </document:ClrSys>\n");
            xml.append("                </document:SttlmInf>\n");
            xml.append("                <document:PmtTpInf>\n");
            xml.append("                    <document:LclInstrm>\n");
            xml.append("                        <document:Prtry>").append(escapeXml(localInstrument)).append("</document:Prtry>\n");
            xml.append("                    </document:LclInstrm>\n");
            xml.append("                    <document:CtgyPurp>\n");
            xml.append("                        <document:Prtry>").append(escapeXml(categoryPurpose)).append("</document:Prtry>\n");
            xml.append("                    </document:CtgyPurp>\n");
            xml.append("                </document:PmtTpInf>\n");
            xml.append("                <document:InstgAgt>\n");
            xml.append("                    <document:FinInstnId>\n");
            xml.append("                        <document:Othr>\n");
            xml.append("                            <document:Id>").append(escapeXml(instructingAgentId)).append("</document:Id>\n");
            xml.append("                        </document:Othr>\n");
            xml.append("                    </document:FinInstnId>\n");
            xml.append("                </document:InstgAgt>\n");
            xml.append("                <document:InstdAgt>\n");
            xml.append("                    <document:FinInstnId>\n");
            xml.append("                        <document:Othr>\n");
            xml.append("                            <document:Id>").append(escapeXml(instructedAgentId)).append("</document:Id>\n");
            xml.append("                        </document:Othr>\n");
            xml.append("                    </document:FinInstnId>\n");
            xml.append("                </document:InstdAgt>\n");
            xml.append("            </document:GrpHdr>\n");
            xml.append("            <document:CdtTrfTxInf>\n");
            xml.append("                <document:PmtId>\n");
            xml.append("                    <document:EndToEndId>").append(escapeXml(endToEndId)).append("</document:EndToEndId>\n");
            xml.append("                    <document:TxId>").append(escapeXml(txId)).append("</document:TxId>\n");
            xml.append("                </document:PmtId>\n");
            xml.append("                <document:IntrBkSttlmAmt Ccy=\"").append(escapeXml(currency)).append("\">")
                    .append(escapeXml(amount)).append("</document:IntrBkSttlmAmt>\n");
            xml.append("                <document:AccptncDtTm>").append(escapeXml(createDtTm)).append("</document:AccptncDtTm>\n");
            xml.append("                <document:InstdAmt Ccy=\"").append(escapeXml(currency)).append("\">")
                    .append(escapeXml(amount)).append("</document:InstdAmt>\n");
            xml.append("                <document:ChrgBr>").append(escapeXml(chargeBearer)).append("</document:ChrgBr>\n");

            // UltmtDbtr (Ultimate Debtor - matches sample)
//            if (debtorName != null) {
//                xml.append("                <document:UltmtDbtr>\n");
//                xml.append("                    <document:Nm>").append(escapeXml(debtorName)).append("</document:Nm>\n");
//                if (creditorCountry != null) {
//                    xml.append("                    <document:PstlAdr>\n");
//                    xml.append("                        <document:Ctry>").append(escapeXml(creditorCountry)).append("</document:Ctry>\n");
//                    xml.append("                    </document:PstlAdr>\n");
//                }
//                xml.append("                </document:UltmtDbtr>\n");
//            }

            // Dbtr (Debtor) - MATCHES SAMPLE EXACTLY
            xml.append("                <document:Dbtr>\n");
            xml.append("                    <document:Nm>").append(escapeXml(debtorName)).append("</document:Nm>\n");
            xml.append("                    <document:PstlAdr>\n");
            xml.append("                        <document:AdrLine>").append(escapeXml(debtorAddressLine)).append("</document:AdrLine>\n");
            xml.append("                    </document:PstlAdr>\n");
            if (debtorPrivateIdScheme != null) {
                xml.append("                    <document:Id>\n");
                xml.append("                        <document:PrvtId>\n");
                xml.append("                            <document:Othr>\n");
                xml.append("                                <document:Id>").append(escapeXml(debtorPrivateIdScheme)).append("</document:Id>\n");
                xml.append("                                <document:SchmeNm>\n");
                xml.append("                                    <document:Prtry>").append(escapeXml(debtorPrivateId)).append("</document:Prtry>\n");
                xml.append("                                </document:SchmeNm>\n");
                xml.append("                            </document:Othr>\n");
                xml.append("                        </document:PrvtId>\n");
                xml.append("                    </document:Id>\n");
            }
            if (debtorMobile != null || debtorEmail != null) {
                xml.append("                    <document:CtctDtls>\n");
                if (debtorMobile != null) {
                    xml.append("                        <document:MobNb>").append(escapeXml(debtorMobile)).append("</document:MobNb>\n");
                }
                if (debtorEmail != null) {
                    xml.append("                        <document:EmailAdr>").append(escapeXml(debtorEmail)).append("</document:EmailAdr>\n");
                }
                xml.append("                    </document:CtctDtls>\n");
            }
            xml.append("                </document:Dbtr>\n");

            // DbtrAcct - MATCHES SAMPLE EXACTLY
            xml.append("                <document:DbtrAcct>\n");
            xml.append("                    <document:Id>\n");
            xml.append("                        <document:Othr>\n");
            xml.append("                            <document:Id>").append(escapeXml(debtorAcctId)).append("</document:Id>\n");
            xml.append("                            <document:SchmeNm>\n");
            xml.append("                                <document:Prtry>").append(escapeXml(debtorClearingType)).append("</document:Prtry>\n");
            xml.append("                            </document:SchmeNm>\n");
            xml.append("                            <document:Issr>C</document:Issr>\n");
            xml.append("                        </document:Othr>\n");
            xml.append("                    </document:Id>\n");
            xml.append("                </document:DbtrAcct>\n");

            // DbtrAgt - MATCHES SAMPLE EXACTLY
            xml.append("                <document:DbtrAgt>\n");
            xml.append("                    <document:FinInstnId>\n");
            xml.append("                        <document:Othr>\n");
            xml.append("                            <document:Id>").append(escapeXml(instructingAgentId)).append("</document:Id>\n");
            xml.append("                            <document:Issr>ATM</document:Issr>\n");
            xml.append("                        </document:Othr>\n");
            xml.append("                    </document:FinInstnId>\n");
            xml.append("                </document:DbtrAgt>\n");

            // CdtrAgt - MATCHES SAMPLE EXACTLY
            xml.append("                <document:CdtrAgt>\n");
            xml.append("                    <document:FinInstnId>\n");
            xml.append("                        <document:Othr>\n");
            xml.append("                            <document:Id>").append(escapeXml(instructedAgentId)).append("</document:Id>\n");
            xml.append("                        </document:Othr>\n");
            xml.append("                    </document:FinInstnId>\n");
            xml.append("                </document:CdtrAgt>\n");

            // Cdtr (Creditor) - MATCHES SAMPLE EXACTLY
            xml.append("                <document:Cdtr>\n");
            xml.append("                    <document:Nm>").append(escapeXml(creditorName)).append("</document:Nm>\n");
            xml.append("                    <document:PstlAdr>\n");
            if (creditorTownName != null && !creditorTownName.isEmpty()) {
                xml.append("                        <document:TwnNm>").append(escapeXml(creditorTownName)).append("</document:TwnNm>\n");
            }
            if (creditorCountry != null && !creditorCountry.isEmpty()) {
                xml.append("                        <document:Ctry>").append(escapeXml(creditorCountry)).append("</document:Ctry>\n");
            }
            xml.append("                    </document:PstlAdr>\n");

            // Organization ID (MCC)
            if (creditorOrgId != null && !creditorOrgId.isEmpty()) {
                xml.append("                    <document:Id>\n");
                xml.append("                        <document:OrgId>\n");
                xml.append("                            <document:Othr>\n");
                xml.append("                                <document:Id>").append(escapeXml(creditorOrgId)).append("</document:Id>\n");
                xml.append("                            </document:Othr>\n");
                xml.append("                        </document:OrgId>\n");
                xml.append("                    </document:Id>\n");
            }

            // Country of Residence
            if (creditorCountryOfRes != null && !creditorCountryOfRes.isEmpty()) {
                xml.append("                    <document:CtryOfRes>").append(escapeXml(creditorCountryOfRes)).append("</document:CtryOfRes>\n");
            }

            // Contact Details - MATCHES SAMPLE WITH ALL FIELDS
            xml.append("                    <document:CtctDtls>\n");
            xml.append("                        <document:Nm>").append(escapeXml(creditorName)).append("</document:Nm>\n");
            // Mobile number goes here (below merchant name)
            if (mobileNumber != null && !mobileNumber.isEmpty()) {
                xml.append("                        <document:MobNb>").append(escapeXml(mobileNumber)).append("</document:MobNb>\n");
            }
            // Store label goes in Dept field
            if (storeLabel != null && !storeLabel.isEmpty()) {
                xml.append("                        <document:Dept>").append(escapeXml(storeLabel)).append("</document:Dept>\n");
            }
            xml.append("                        <document:Othr>\n");
            xml.append("                            <document:ChanlTp>").append(escapeXml(merchantChannel != null ? merchantChannel : "QRCP")).append("</document:ChanlTp>\n");
            // Terminal ID goes here
            if (terminalId != null && !terminalId.isEmpty()) {
                xml.append("                            <document:Id>").append(escapeXml(terminalId)).append("</document:Id>\n");
            }
            xml.append("                        </document:Othr>\n");
            xml.append("                    </document:CtctDtls>\n");
            xml.append("                </document:Cdtr>\n");

            // CdtrAcct - MATCHES SAMPLE EXACTLY
            xml.append("                <document:CdtrAcct>\n");
            xml.append("                    <document:Id>\n");
            xml.append("                        <document:Othr>\n");
            xml.append("                            <document:Id>").append(escapeXml(creditorAcctId)).append("</document:Id>\n");
            xml.append("                            <document:SchmeNm>\n");
            xml.append("                                <document:Prtry>").append(escapeXml(creditorClearingType)).append("</document:Prtry>\n");
            xml.append("                            </document:SchmeNm>\n");
            xml.append("                        </document:Othr>\n");
            xml.append("                    </document:Id>\n");
            xml.append("                </document:CdtrAcct>\n");

            // UltmtCdtr - FOR QR TAG 02 (Mobile Number for Top Up only)
            if (mobileNumber != null && !mobileNumber.isEmpty()) {
                xml.append("                <document:UltmtCdtr>\n");
                xml.append("                    <document:Id>\n");
                xml.append("                        <document:PrvtId>\n");
                xml.append("                            <document:Othr>\n");
                xml.append("                                <document:Id>").append(escapeXml(mobileNumber)).append("</document:Id>\n");
                xml.append("                                <document:SchmeNm>\n");
                xml.append("                                    <document:Prtry>MOBN</document:Prtry>\n");
                xml.append("                                </document:SchmeNm>\n");
                xml.append("                            </document:Othr>\n");
                xml.append("                        </document:PrvtId>\n");
                xml.append("                    </document:Id>\n");
                xml.append("                </document:UltmtCdtr>\n");
            }

            // InstrForNxtAgt - MATCHES SAMPLE EXACTLY for bill paymenet only as per the spec and comment stay here always
//            if (instructionForNextAgent != null && !instructionForNextAgent.isEmpty()) {
//                xml.append("                <document:InstrForNxtAgt>\n");
//                xml.append("                    <document:InstrInf>").append(escapeXml(instructionForNextAgent)).append("</document:InstrInf>\n");
//                xml.append("                </document:InstrForNxtAgt>\n");
//            }

            // Purp - MATCHES SAMPLE EXACTLY
            xml.append("                <document:Purp>\n");
            xml.append("                    <document:Prtry>").append(escapeXml(purposeCode)).append("</document:Prtry>\n");
            xml.append("                </document:Purp>\n");

            // Tax - MATCHES SAMPLE EXACTLY
            if (merchantTaxId != null && !merchantTaxId.isEmpty()) {
                xml.append("                <document:Tax>\n");
                xml.append("                    <document:Cdtr>\n");
                xml.append("                        <document:TaxId>").append(escapeXml(merchantTaxId)).append("</document:TaxId>\n");
                xml.append("                    </document:Cdtr>\n");
                xml.append("                </document:Tax>\n");
            }

            // RmtInf (Remittance Information) - Updated with Bill Number in correct location
            xml.append("                <document:RmtInf>\n");
            String unstructuredRemittance = buildRemittanceString();
            if (unstructuredRemittance != null && !unstructuredRemittance.isEmpty()) {
                xml.append("                    <document:Ustrd>").append(escapeXml(unstructuredRemittance)).append("</document:Ustrd>\n");
            }

// Structured remittance for Bill Number AND Tip
            boolean hasBillNumber = billNumber != null && !billNumber.isEmpty();
            boolean hasTip = tipAmount != null && !tipAmount.isEmpty() && new BigDecimal(tipAmount).compareTo(BigDecimal.ZERO) > 0;

            if (hasBillNumber || hasTip) {
                xml.append("                    <document:Strd>\n");

                // BILL NUMBER - Correct location: /RmtInf/Strd/RfrdDocInf/Nb
                if (hasBillNumber) {
                    xml.append("                        <document:RfrdDocInf>\n");
                    xml.append("                            <document:Nb>").append(escapeXml(billNumber)).append("</document:Nb>\n");
                    xml.append("                        </document:RfrdDocInf>\n");
                }

                // TIP AMOUNT - RfrdDocAmt/DuePyblAmt
                if (hasTip) {
                    xml.append("                        <document:RfrdDocAmt>\n");
                    xml.append("                            <document:DuePyblAmt Ccy=\"").append(escapeXml(currency)).append("\">")
                            .append(escapeXml(tipAmount)).append("</document:DuePyblAmt>\n");
                    xml.append("                        </document:RfrdDocAmt>\n");
                }

                xml.append("                    </document:Strd>\n");
            }

            xml.append("                </document:RmtInf>\n");

            xml.append("            </document:CdtTrfTxInf>\n");
            xml.append("        </document:FIToFICstmrCdtTrf>\n");
            xml.append("    </document:Document>\n");
            xml.append("</FPEnvelope>");

            return xml.toString();
        }
    }
}