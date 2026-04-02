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

    @Value("${payment.debtor.mobile:+251-900000000}")
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

    public String getAccessToken(String transactionId) throws Exception {
        transactionLogger.log(transactionId, "--- 1. Requesting Access Token ---");
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(tokenUrl);

            httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
            httpPost.setHeader("jwt-assertion", jwtAssertion);
            httpPost.setHeader(HttpHeaders.USER_AGENT, "Apache-HttpClient/4.5.14 (Java/21.0.9)");

            String body = "grant_type=password&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) +
                    "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            httpPost.setEntity(new StringEntity(body));

            transactionLogger.log(transactionId, "Token Request URL: " + tokenUrl);
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

    public Map<String, String> processPayment(QRCodeData qrData, PaymentRequest userInput, String transactionId) throws Exception {
        String endToEndId = generateEndToEndId();
        String txId = generateTransactionId();
        String uetr = qrData.getUetr() != null ? qrData.getUetr() : UUID.randomUUID().toString();

        // Calculate total amount including tip
        BigDecimal totalAmount = calculateTotalAmount(qrData, userInput);
        String currency = qrData.getTransactionCurrency() != null ?
                getCurrencyCode(qrData.getTransactionCurrency()) : defaultCurrency;

        Pacs008Message message = buildPacs008Message(qrData, userInput, transactionId,
                endToEndId, txId, uetr, totalAmount, currency);
        String pacs008 = message.toXml();


        String signedPacs008 = getDigestedMessage(pacs008, transactionId);
        String accessToken = getAccessToken(transactionId);
        String responseXml = sendPaymentRequest(signedPacs008, accessToken, transactionId);

        Map<String, String> result = new HashMap<>();
        result.put("endToEndId", endToEndId);
        result.put("txId", txId);
        result.put("uetr", uetr);
        result.put("totalAmount", totalAmount.toString());
        result.put("currency", currency);
        result.put("response", responseXml);
        return result;
    }

    private BigDecimal calculateTotalAmount(QRCodeData qrData, PaymentRequest userInput) {
        BigDecimal baseAmount = BigDecimal.ZERO;

        // Get base amount from QR or user input
        if (qrData.getTransactionAmount() != null && !qrData.getTransactionAmount().isEmpty()) {
            baseAmount = new BigDecimal(qrData.getTransactionAmount());
        } else if (userInput.getAmount() != null) {
            baseAmount = userInput.getAmount();
        }

        // Add tip if present
        String tipIndicator = qrData.getTipOrConvenienceIndicator();
        BigDecimal tipAmount = BigDecimal.ZERO;

        if (userInput.getTipAmount() != null) {
            tipAmount = userInput.getTipAmount();
        } else if (tipIndicator != null) {
            switch (tipIndicator) {
                case "01": // Prompted to customer - use user input
                    if (userInput.getTipAmount() != null) {
                        tipAmount = userInput.getTipAmount();
                    }
                    break;
                case "02": // Fixed tip amount
                    if (qrData.getValueOfConvenienceFeeFixed() != null && !qrData.getValueOfConvenienceFeeFixed().isEmpty()) {
                        tipAmount = new BigDecimal(qrData.getValueOfConvenienceFeeFixed());
                    }
                    break;
                case "03": // Percentage tip
                    if (qrData.getValueOfConvenienceFeePercentage() != null && !qrData.getValueOfConvenienceFeePercentage().isEmpty()) {
                        BigDecimal percentage = new BigDecimal(qrData.getValueOfConvenienceFeePercentage());
                        tipAmount = baseAmount.multiply(percentage).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
                    }
                    break;
            }
        }

        return baseAmount.add(tipAmount);
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
                                               BigDecimal totalAmount, String currency) {
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
        String instructedAgentId = "VITAETAA";
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
        String remittanceUnstructured = buildRemittanceUnstructured(qrData, userInput);
        String billNumber = extractBillNumber(qrData, userInput);
        String mobileNumber = extractMobileNumber(qrData, userInput);
        String storeLabel = extractStoreLabel(qrData);
        String loyaltyNumber = extractLoyaltyNumber(qrData);
        String terminalId = extractTerminalId(qrData);

        // Check for additional consumer data request (tag 62 sub 09)
        String additionalConsumerData = extractAdditionalConsumerDataRequest(qrData);
        boolean requestAddress = additionalConsumerData != null && additionalConsumerData.contains("A");
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
        String debtorName = userInput.getCustomerName() != null ?
                userInput.getCustomerName() : defaultDebtorName;
        String debtorAddress = (consumerAddress != null) ? consumerAddress : defaultDebtorAddressLine;
        String debtorMobile = (consumerMobile != null) ? consumerMobile :
                (userInput.getCustomerMobile() != null ? userInput.getCustomerMobile() : defaultDebtorMobile);
        String debtorEmail = (consumerEmail != null) ? consumerEmail :
                (userInput.getCustomerEmail() != null ? userInput.getCustomerEmail() : defaultDebtorEmail);
        String debtorAcctId = userInput.getCustomerAccountId() != null ?
                userInput.getCustomerAccountId() : defaultDebtorAcctId;

        // Build creditor info (merchant info)
        String creditorName = qrData.getMerchantName() != null ? qrData.getMerchantName() : "Merchant";
        String creditorTownName = qrData.getMerchantCity() != null ? qrData.getMerchantCity() : "";
        String merchantTaxId = extractMerchantTaxId(qrData);
        String merchantChannel = extractMerchantChannel(qrData);

        // Determine if this is a bill payment
        boolean isBillPayment = "C2BBPT".equals(categoryPurpose) || billNumber != null;

        // Extract ultimate creditor (could be biller)
        String ultimateCreditorId = billNumber;

        // Generate unique IDs
        String bizMsgId = instructingAgentId + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String msgId = instructingAgentId + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        return new Pacs008Message()
                .withBizMsgId(bizMsgId)
                .withMsgId(msgId)
                .withCreateDt(createDt)
                .withCreateDtTm(createDtTm)
                .withSigningTime(signingTime)
                .withInstructingAgentId(instructingAgentId)
                .withInstructedAgentId(instructedAgentId)
                .withEndToEndId(endToEndId)
                .withTxId(txId)
                .withUetr(uetr)
                .withCurrency(currency)
                .withAmount(totalAmount.toString())
                .withChargeBearer(chargeBearer)
                .withLocalInstrument(localInstrument)
                .withCategoryPurpose(categoryPurpose)
                .withPurposeCode(purposeCode)
                // Debtor info (Customer)
                .withDebtorName(debtorName)
                .withDebtorAddressLine(debtorAddress)
                .withDebtorMobile(debtorMobile)
                .withDebtorEmail(debtorEmail)
                .withDebtorAcctId(debtorAcctId)
                .withDebtorClearingType(defaultDebtorClearingType)
                .withDebtorPrivateId(defaultDebtorPrivateId)
                .withDebtorPrivateIdScheme(defaultDebtorPrivateIdScheme)
                // Creditor info (Merchant)
                .withCreditorName(creditorName)
                .withCreditorTownName(creditorTownName)
                .withCreditorCountryOfRes("ET")
                .withCreditorContactChannel(merchantChannel != null ? merchantChannel : "QRCP")
                .withCreditorAcctId(creditorAcctId)
                .withCreditorClearingType(clearingType)
                // Optional fields
                .withUltimateCreditorId(ultimateCreditorId)
                .withMerchantTaxId(merchantTaxId)
                .withInstructionForNextAgent(isBillPayment ? instructedAgentId : null)
                .withRemittanceUnstructured(remittanceUnstructured)
                .withBillNumber(billNumber)
                .withMobileNumber(mobileNumber)
                .withStoreLabel(storeLabel)
                .withLoyaltyNumber(loyaltyNumber)
                .withTerminalId(terminalId);
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
                if (purpose.toLowerCase().contains("bill")) return "C2BBPT";
                if (purpose.toLowerCase().contains("merchant")) return "C2BSQR";
            }
        }

        String mcc = qrData.getMerchantCategoryCode();
        if (mcc != null) {
            // Bill payment MCCs (utilities, telecom, etc.)
            if (mcc.startsWith("48") || mcc.startsWith("49") || mcc.equals("9399")) {
                return "C2BBPT";
            }
        }

        if (qrData.getBillNumber() != null) {
            return "C2BBPT";
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
                if (purpose.toLowerCase().contains("online")) return "ONLPUR";
                if (purpose.toLowerCase().contains("salary")) return "SALA";
                if (purpose.toLowerCase().contains("government")) return "GOVT";
            }
        }

        return defaultPurposeCode;
    }

    private String buildRemittanceUnstructured(QRCodeData qrData, PaymentRequest userInput) {
        StringBuilder sb = new StringBuilder();

        if (userInput.getRemittanceInfo() != null && !userInput.getRemittanceInfo().isEmpty()) {
            sb.append(userInput.getRemittanceInfo());
        }

        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null) {
            if (additionalData.containsKey("05") && sb.length() == 0) {
                sb.append(additionalData.get("05"));
            }
            if (additionalData.containsKey("08") && sb.length() == 0) {
                sb.append(additionalData.get("08"));
            }
        }

        if (qrData.getContextOfTransaction() != null && sb.length() == 0) {
            sb.append(qrData.getContextOfTransaction());
        }

        if (sb.length() == 0) {
            sb.append("QR Payment");
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
        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null && additionalData.containsKey("11")) {
            return additionalData.get("11");
        }
        return null;
    }

    private String generateEndToEndId() {
        return instructingAgentId + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String generateTransactionId() {
        return instructingAgentId + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
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

    private String sendPaymentRequest(String signedXml, String accessToken, String transactionId) throws Exception {
        transactionLogger.log(transactionId, "--- 4. Sending Final Payment Request ---");
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(incomingUrl);
            httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/xml");
            httpPost.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            httpPost.setEntity(new StringEntity(signedXml, StandardCharsets.UTF_8));

            transactionLogger.log(transactionId, "Payment Request URL: " + incomingUrl);
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
            xml.append("            </header:FIId>\n");
            xml.append("        </header:Fr>\n");
            xml.append("        <header:To>\n");
            xml.append("            <header:FIId>\n");
            xml.append("                <header:FinInstnId>\n");
            xml.append("                    <header:Othr>\n");
            xml.append("                        <header:Id>").append(escapeXml(instructedAgentId)).append("</header:Id>\n");
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
            xml.append("                <document:IntrBkSttlmAmt Ccy=\"").append(escapeXml(currency)).append("\">").append(escapeXml(amount)).append("</document:IntrBkSttlmAmt>\n");
            xml.append("                <document:AccptncDtTm>").append(escapeXml(createDtTm)).append("</document:AccptncDtTm>\n");
            xml.append("                <document:InstdAmt Ccy=\"").append(escapeXml(currency)).append("\">").append(escapeXml(amount)).append("</document:InstdAmt>\n");
            xml.append("                <document:ChrgBr>").append(escapeXml(chargeBearer)).append("</document:ChrgBr>\n");

            // Debtor (Customer)
            xml.append("                <document:Dbtr>\n");
            xml.append("                    <document:Nm>").append(escapeXml(debtorName)).append("</document:Nm>\n");
            xml.append("                    <document:PstlAdr>\n");
            xml.append("                        <document:AdrLine>").append(escapeXml(debtorAddressLine)).append("</document:AdrLine>\n");
            xml.append("                    </document:PstlAdr>\n");
            if (debtorPrivateId != null) {
                xml.append("                    <document:Id>\n");
                xml.append("                        <document:PrvtId>\n");
                xml.append("                            <document:Othr>\n");
                xml.append("                                <document:Id>").append(escapeXml(debtorPrivateId)).append("</document:Id>\n");
                xml.append("                                <document:SchmeNm>\n");
                xml.append("                                    <document:Prtry>").append(escapeXml(debtorPrivateIdScheme)).append("</document:Prtry>\n");
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

            // Debtor Account
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

            // Debtor Agent
            xml.append("                <document:DbtrAgt>\n");
            xml.append("                    <document:FinInstnId>\n");
            xml.append("                        <document:Othr>\n");
            xml.append("                            <document:Id>").append(escapeXml(instructingAgentId)).append("</document:Id>\n");
            xml.append("                            <document:Issr>ATM</document:Issr>\n");
            xml.append("                        </document:Othr>\n");
            xml.append("                    </document:FinInstnId>\n");
            xml.append("                </document:DbtrAgt>\n");

            // Creditor Agent
            xml.append("                <document:CdtrAgt>\n");
            xml.append("                    <document:FinInstnId>\n");
            xml.append("                        <document:Othr>\n");
            xml.append("                            <document:Id>").append(escapeXml(instructedAgentId)).append("</document:Id>\n");
            xml.append("                        </document:Othr>\n");
            xml.append("                    </document:FinInstnId>\n");
            xml.append("                </document:CdtrAgt>\n");

            // Creditor (Merchant) - Updated with all optional fields
            xml.append("                <document:Cdtr>\n");
            xml.append("                    <document:Nm>").append(escapeXml(creditorName)).append("</document:Nm>\n");

            // Full Postal Address
            if (creditorStreetName != null || creditorBuildingNumber != null ||
                    creditorPostalCode != null || creditorTownName != null ||
                    creditorCountry != null || creditorAddressLine != null) {
                xml.append("                    <document:PstlAdr>\n");
                if (creditorStreetName != null && !creditorStreetName.isEmpty()) {
                    xml.append("                        <document:StrtNm>").append(escapeXml(creditorStreetName)).append("</document:StrtNm>\n");
                }
                if (creditorBuildingNumber != null && !creditorBuildingNumber.isEmpty()) {
                    xml.append("                        <document:BldgNb>").append(escapeXml(creditorBuildingNumber)).append("</document:BldgNb>\n");
                }
                if (creditorPostalCode != null && !creditorPostalCode.isEmpty()) {
                    xml.append("                        <document:PstCd>").append(escapeXml(creditorPostalCode)).append("</document:PstCd>\n");
                }
                if (creditorTownName != null && !creditorTownName.isEmpty()) {
                    xml.append("                        <document:TwnNm>").append(escapeXml(creditorTownName)).append("</document:TwnNm>\n");
                }
                if (creditorCountry != null && !creditorCountry.isEmpty()) {
                    xml.append("                        <document:Ctry>").append(escapeXml(creditorCountry)).append("</document:Ctry>\n");
                }
                if (creditorAddressLine != null && !creditorAddressLine.isEmpty()) {
                    xml.append("                        <document:AdrLine>").append(escapeXml(creditorAddressLine)).append("</document:AdrLine>\n");
                }
                xml.append("                    </document:PstlAdr>\n");
            }

            // Organization ID (MCC code)
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

            // Contact Details
            if (creditorContactChannel != null) {
                xml.append("                    <document:CtctDtls>\n");
                xml.append("                        <document:Nm>").append(escapeXml(creditorName)).append("</document:Nm>\n");
                xml.append("                        <document:Othr>\n");
                xml.append("                            <document:ChanlTp>").append(escapeXml(creditorContactChannel)).append("</document:ChanlTp>\n");
                xml.append("                        </document:Othr>\n");
                xml.append("                    </document:CtctDtls>\n");
            }

            xml.append("                </document:Cdtr>\n");

            // Creditor Account
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

            // Ultimate Creditor (if bill payment)
            if (ultimateCreditorId != null && !ultimateCreditorId.isEmpty()) {
                xml.append("                <document:UltmtCdtr>\n");
                xml.append("                    <document:Nm>").append(escapeXml(ultimateCreditorId)).append("</document:Nm>\n");
                xml.append("                </document:UltmtCdtr>\n");
            }

            // Purpose
            xml.append("                <document:Purp>\n");
            xml.append("                    <document:Prtry>").append(escapeXml(purposeCode)).append("</document:Prtry>\n");
            xml.append("                </document:Purp>\n");

            // InstructionForNextAgent (for bill payments)
            if (instructionForNextAgent != null && !instructionForNextAgent.isEmpty()) {
                xml.append("                <document:InstrForNxtAgt>\n");
                xml.append("                    <document:InstrInf>").append(escapeXml(instructionForNextAgent)).append("</document:InstrInf>\n");
                xml.append("                </document:InstrForNxtAgt>\n");
            }

            // Tax (if merchant tax ID exists)
            if (merchantTaxId != null && !merchantTaxId.isEmpty()) {
                xml.append("                <document:Tax>\n");
                xml.append("                    <document:Cdtr>\n");
                xml.append("                        <document:TaxId>").append(escapeXml(merchantTaxId)).append("</document:TaxId>\n");
                xml.append("                    </document:Cdtr>\n");
                xml.append("                </document:Tax>\n");
            }

            // Remittance Information
            xml.append("                <document:RmtInf>\n");

// Unstructured remittance (always include if present)
            if (remittanceUnstructured != null && !remittanceUnstructured.isEmpty()) {
                xml.append("                    <document:Ustrd>").append(escapeXml(remittanceUnstructured)).append("</document:Ustrd>\n");
            }

// Structured remittance - ONLY include if there is a tip amount
            if (tipAmount != null && !tipAmount.isEmpty() && new BigDecimal(tipAmount).compareTo(BigDecimal.ZERO) > 0) {
                xml.append("                    <document:Strd>\n");
                xml.append("                        <document:RfrdDocAmt>\n");
                xml.append("                            <document:DuePyblAmt Ccy=\"").append(escapeXml(currency)).append("\">").append(escapeXml(tipAmount)).append("</document:DuePyblAmt>\n");
                xml.append("                        </document:RfrdDocAmt>\n");
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