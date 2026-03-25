package com.example.EthQR.service;

import com.example.EthQR.model.QRCodeData;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    public Map<String, String> processPayment(QRCodeData qrData, String transactionId) throws Exception {
        String endToEndId = "BBANKETA" + System.currentTimeMillis();
        String pacs008 = buildPacs008Message(qrData, transactionId, endToEndId);
        String signedPacs008 = getDigestedMessage(pacs008, transactionId);
        String accessToken = getAccessToken(transactionId);
        String responseXml = sendPaymentRequest(signedPacs008, accessToken, transactionId);
        
        return Map.of("endToEndId", endToEndId, "response", responseXml);
    }

    private String buildPacs008Message(QRCodeData qrData, String transactionId, String endToEndId) {
        transactionLogger.log(transactionId, "--- 2. Building pacs.008 Message from QR Data ---");

        String bizMsgId = "BBANKETA" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String msgId = "BBANKETA" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        
        DateTimeFormatter offsetFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        String createDtTm = LocalDateTime.now().atZone(ZoneId.systemDefault()).format(offsetFormatter);
        String createDt = createDtTm;

        String amount = qrData.getTransactionAmount() != null ? qrData.getTransactionAmount() : "0.00";
        String currency = "ETB";
        
        String creditorName = qrData.getMerchantName() != null ? qrData.getMerchantName() : "N/A";
        
        String instructedAgentId = "ETSETAA";
        String creditorAcctId = "000000000000";
        Map<String, String> mai = qrData.getMerchantAccountInformation();
        if (mai != null && mai.containsKey("28")) {
            String[] parts = mai.get("28").split("\\|");
            if (parts.length >= 3) {
                instructedAgentId = parts[1];
                creditorAcctId = parts[2];
            }
        }

        String purposeCode = "C2BSQR"; // Use the standard code for QR payments
        String remittanceInfo = "QR Payment";
        Map<String, String> additionalData = qrData.getAdditionalDataField();
        if (additionalData != null) {
            // Use QR data for descriptive remittance info, not for the category purpose code
            if (additionalData.containsKey("08")) { remittanceInfo = additionalData.get("08"); }
            if (additionalData.containsKey("05")) { remittanceInfo = additionalData.get("05"); }
        }
        if (qrData.getContextOfTransaction() != null && !qrData.getContextOfTransaction().isEmpty()) {
            remittanceInfo = qrData.getContextOfTransaction();
        }

        String instructingAgentId = "BBANKETA";
        String debtorName = "YESHAMBLE AMARE";
        String debtorAcctId = "2305130000483";

        String xml = String.format("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <FPEnvelope xmlns="urn:iso:std:iso:20022:tech:xsd:payment_request" xmlns:document="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.10" xmlns:header="urn:iso:std:iso:20022:tech:xsd:head.001.001.03">
                    <header:AppHdr>
                        <header:Fr><header:FIId><header:FinInstnId><header:Othr><header:Id>%s</header:Id></header:Othr></header:FinInstnId></header:FIId></header:Fr>
                        <header:To><header:FIId><header:FinInstnId><header:Othr><header:Id>FP</header:Id></header:Othr></header:FinInstnId></header:FIId></header:To>
                        <header:BizMsgIdr>%s</header:BizMsgIdr>
                        <header:MsgDefIdr>pacs.008.001.10</header:MsgDefIdr>
                        <header:CreDt>%s</header:CreDt>
                    </header:AppHdr>
                    <document:Document>
                        <document:FIToFICstmrCdtTrf>
                            <document:GrpHdr>
                                <document:MsgId>%s</document:MsgId>
                                <document:CreDtTm>%s</document:CreDtTm>
                                <document:NbOfTxs>1</document:NbOfTxs>
                                <document:SttlmInf><document:SttlmMtd>CLRG</document:SttlmMtd><document:ClrSys><document:Prtry>FP</document:Prtry></document:ClrSys></document:SttlmInf>
                                <document:PmtTpInf><document:LclInstrm><document:Prtry>CRTRM</document:Prtry></document:LclInstrm><document:CtgyPurp><document:Prtry>%s</document:Prtry></document:CtgyPurp></document:PmtTpInf>
                                <document:InstgAgt><document:FinInstnId><document:Othr><document:Id>%s</document:Id></document:Othr></document:FinInstnId></document:InstgAgt>
                                <document:InstdAgt><document:FinInstnId><document:Othr><document:Id>%s</document:Id></document:Othr></document:FinInstnId></document:InstdAgt>
                            </document:GrpHdr>
                            <document:CdtTrfTxInf>
                                <document:PmtId><document:EndToEndId>%s</document:EndToEndId><document:TxId>%s</document:TxId></document:PmtId>
                                <document:IntrBkSttlmAmt Ccy="%s">%s</document:IntrBkSttlmAmt>
                                <document:AccptncDtTm>%s</document:AccptncDtTm>
                                <document:InstdAmt Ccy="%s">%s</document:InstdAmt>
                                <document:ChrgBr>SLEV</document:ChrgBr>
                                <document:Dbtr><document:Nm>%s</document:Nm><document:PstlAdr><document:AdrLine>Address</document:AdrLine></document:PstlAdr></document:Dbtr>
                                <document:DbtrAcct><document:Id><document:Othr><document:Id>%s</document:Id><document:SchmeNm><document:Prtry>ACCT</document:Prtry></document:SchmeNm><document:Issr>C</document:Issr></document:Othr></document:Id></document:DbtrAcct>
                                <document:DbtrAgt><document:FinInstnId><document:Othr><document:Id>%s</document:Id><document:Issr>ATM</document:Issr></document:Othr></document:FinInstnId></document:DbtrAgt>
                                <document:CdtrAgt><document:FinInstnId><document:Othr><document:Id>%s</document:Id></document:Othr></document:FinInstnId></document:CdtrAgt>
                                <document:Cdtr><document:Nm>%s</document:Nm></document:Cdtr>
                                <document:CdtrAcct><document:Id><document:Othr><document:Id>%s</document:Id><document:SchmeNm><document:Prtry>ACCT</document:Prtry></document:SchmeNm></document:Othr></document:Id></document:CdtrAcct>
                                <document:RmtInf><document:Ustrd>%s</document:Ustrd></document:RmtInf>
                            </document:CdtTrfTxInf>
                        </document:FIToFICstmrCdtTrf>
                    </document:Document>
                </FPEnvelope>
                """,
                instructingAgentId, bizMsgId, createDt, msgId, createDtTm, purposeCode, instructingAgentId, instructedAgentId, endToEndId, endToEndId,
                currency, amount, createDtTm, currency, amount, debtorName, debtorAcctId, instructingAgentId,
                instructedAgentId, creditorName, creditorAcctId, remittanceInfo
        );

        transactionLogger.log(transactionId, "Constructed pacs.008 Message:\n" + xml);
        return xml;
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
                        String reason = xPath.compile("//*[local-name()='RsnDesc']/text()").evaluate(doc);
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
}
