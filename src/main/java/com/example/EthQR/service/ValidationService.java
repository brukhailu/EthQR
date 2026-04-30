package com.example.EthQR.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

import javax.annotation.PostConstruct;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

@Service
public class ValidationService {

    private List<Map<String, Object>> scenarios = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("scenarios/standard-scenarios.json");
            InputStream inputStream = resource.getInputStream();
            scenarios = objectMapper.readValue(inputStream, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<ValidationResult> validate(String xml, Map<String, Object> qrData, Map<String, String> userInputs, String scenarioId) {
        List<ValidationResult> results = new ArrayList<>();
        try {
            Map<String, Object> selectedScenario = null;
            if (scenarioId != null && !scenarioId.equals("default")) {
                for (Map<String, Object> s : scenarios) {
                    if (String.valueOf(s.get("id")).equals(scenarioId) || String.valueOf(s.get("name")).equals(scenarioId)) {
                        selectedScenario = s;
                        break;
                    }
                }
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes()));

            XPath xPath = XPathFactory.newInstance().newXPath();
            xPath.setNamespaceContext(new NamespaceContextImpl());

            // 1. Static Header Rules (Always Mandatory)
            results.add(validateNode(doc, xPath, "hdr_msg_def", "Msg Definition ID", "//header:MsgDefIdr", "M", "pacs.008.001.10", "Should be pacs.008.001.10"));
            results.add(validateNode(doc, xPath, "hdr_to_id", "Receiver ID (To)", "//header:To//header:FIId//header:Id", "M", "FP", "Receiver identifier (Fast Payment Switch)"));
            results.add(validateNode(doc, xPath, "grp_sttlm_mtd", "Settlement Method", "//document:GrpHdr/document:SttlmInf/document:SttlmMtd", "M", "CLRG", "Must be CLRG"));
            results.add(validateNode(doc, xPath, "grp_lcl_instrm", "Local Instrument", "//document:GrpHdr/document:PmtTpInf/document:LclInstrm/document:Prtry", "M", "CRTRM", "Credit Transfer Regular Mod"));

            // 2. Scenario-Specific Rules
            String expectedPurpose = "C2BSQR"; // Default
            if (selectedScenario != null) {
                // If the scenario has specific business logic, we can override here
                // For now, let's keep it simple and focus on the data match
            }
            results.add(validateNode(doc, xPath, "grp_ctgy_purp", "Category Purpose", "//document:GrpHdr/document:PmtTpInf/document:CtgyPurp/document:Prtry", "M", expectedPurpose, "Category Purpose"));

            // 3. Amount and Currency (QR Dependent)
            if (qrData != null) {
                String cc = (String) qrData.get("53");
                String expectedCcy = "230".equals(cc) ? "ETB" : cc;
                results.add(validateNode(doc, xPath, "tx_ccy", "Currency", "//document:CdtTrfTxInf/document:IntrBkSttlmAmt/@Ccy", "M", expectedCcy, "Currency code"));

                // Amount Logic (Complex calculation)
                results.add(validateAmount(doc, xPath, qrData, userInputs, xml));
            }

            // 4. Merchant Info (QR Dependent)
            if (qrData != null) {
                results.add(validateNode(doc, xPath, "cdtr_nm", "Merchant Name", "//document:CdtTrfTxInf/document:Cdtr/document:Nm", "M", (String) qrData.get("59"), "Merchant Name (Tag 59)"));
                results.add(validateNode(doc, xPath, "cdtr_mcc", "MCC", "//document:CdtTrfTxInf/document:Cdtr/document:Id//document:Id", "M", (String) qrData.get("52"), "MCC (Tag 52)"));
                
                String city = (String) qrData.get("60");
                results.add(validateNode(doc, xPath, "cdtr_city", "Merchant City", "//document:CdtTrfTxInf/document:Cdtr/document:PstlAdr/document:TwnNm", city != null ? "M" : "O", city, "Merchant City (Tag 60)"));
                
                String country = (String) qrData.get("58");
                results.add(validateNode(doc, xPath, "cdtr_ctry", "Merchant Country", "//document:CdtTrfTxInf/document:Cdtr/document:PstlAdr/document:Ctry", country != null ? "M" : "O", country, "Merchant Country (Tag 58)"));
            }

            // 5. Additional Data / Scenario Requirements
            if (qrData != null && qrData.containsKey("62")) {
                Map<String, String> subTags = (Map<String, String>) qrData.get("62");
                
                // Bill Number (Tag 62.01)
                String bill = subTags.get("01");
                if (bill != null) {
                    String expectedBill = "***".equals(bill) ? userInputs.get("62_01") : bill;
                    results.add(validateNode(doc, xPath, "rmt_bill", "Bill Number", "//document:CdtTrfTxInf/document:RmtInf/document:Strd/document:RfrdDocInf/document:Nb", "M", expectedBill, "Bill Number (Tag 62.01)"));
                }

                // Terminal ID (Tag 62.07)
                String terminal = subTags.get("07");
                if (terminal != null) {
                    results.add(validateNode(doc, xPath, "cdtr_term", "Terminal ID", "//document:CdtTrfTxInf/document:Cdtr/document:CtctDtls//document:Id", "M", terminal, "Terminal ID (Tag 62.07)"));
                }
                
                // Loyalty Number (Tag 62.04)
                String loyalty = subTags.get("04");
                if (loyalty != null) {
                    String expectedLoyalty = "***".equals(loyalty) ? userInputs.get("62_04") : loyalty;
                    results.add(validateNode(doc, xPath, "dbtr_loyalty", "Loyalty Number", "//document:CdtTrfTxInf/document:Dbtr/document:Id//document:Id", "M", expectedLoyalty, "Loyalty Number (Tag 62.04)"));
                }
            }

            // 6. Mandatory Payer Info (Always required for PAC.008)
            results.add(validateNode(doc, xPath, "dbtr_nm", "Payer Name", "//document:CdtTrfTxInf/document:Dbtr/document:Nm", "M", userInputs.get("dbtr_nm"), "Payer Name (Prompted)"));
            results.add(validateNode(doc, xPath, "dbtr_acct_schme", "Payer Account Type", "//document:CdtTrfTxInf/document:DbtrAcct/document:Id//document:Prtry", "M", null, "Account Scheme (ACCT, EWLT, etc.)"));

        } catch (Exception e) {
            e.printStackTrace();
        }
        return results;
    }

    private ValidationResult validateNode(Document doc, XPath xPath, String id, String name, String xpathExpr, String type, String expected, String remark) {
        try {
            String actual = (String) xPath.evaluate(xpathExpr, doc, XPathConstants.STRING);
            actual = (actual != null) ? actual.trim() : null;

            String status = "OK";
            if (actual == null || actual.isEmpty()) {
                status = "M".equals(type) ? "MISSING" : "ABSENT";
            } else if (expected != null && !expected.equals(actual)) {
                status = "MISMATCH";
            }

            return new ValidationResult(id, name, type, status, actual, expected, remark);
        } catch (Exception e) {
            return new ValidationResult(id, name, type, "ERROR", null, expected, "XPath Error: " + e.getMessage());
        }
    }

    private ValidationResult validateAmount(Document doc, XPath xPath, Map<String, Object> qrData, Map<String, String> userInputs, String xml) {
        String xpathExpr = "//document:CdtTrfTxInf/document:IntrBkSttlmAmt";
        try {
            String actualStr = (String) xPath.evaluate(xpathExpr, doc, XPathConstants.STRING);
            double actual = (actualStr != null && !actualStr.isEmpty()) ? Double.parseDouble(actualStr) : 0;

            String baseAmtQr = (String) qrData.get("54");
            Double baseAmtVal = 0.0;
            if ("***".equals(baseAmtQr)) {
                String ui = userInputs.get("54");
                if (ui != null) baseAmtVal = Double.parseDouble(ui);
            } else if (baseAmtQr != null) {
                baseAmtVal = Double.parseDouble(baseAmtQr);
            }

            double expected = baseAmtVal;
            String remark = "Base Amount: " + String.format("%.2f", baseAmtVal);

            String tipInd = (String) qrData.get("55");
            if ("01".equals(tipInd)) {
                String tipXml = (String) xPath.evaluate("//document:CdtTrfTxInf/document:RmtInf/document:Strd/document:RfrdDocAmt/document:DuePyblAmt", doc, XPathConstants.STRING);
                if (tipXml != null && !tipXml.isEmpty()) {
                    double tip = Double.parseDouble(tipXml);
                    expected += tip;
                    remark += " + Tip (from XML): " + String.format("%.2f", tip);
                }
            } else if ("02".equals(tipInd)) {
                String fixed = (String) qrData.get("56");
                if (fixed != null) {
                    double tip = Double.parseDouble(fixed);
                    expected += tip;
                    remark += " + Fixed Tip: " + String.format("%.2f", tip);
                }
            } else if ("03".equals(tipInd)) {
                String pct = (String) qrData.get("57");
                if (pct != null) {
                    double tip = (baseAmtVal * Double.parseDouble(pct)) / 100.0;
                    expected += tip;
                    remark += " + " + pct + "% Tip: " + String.format("%.2f", tip);
                }
            }

            String status = (Math.abs(actual - expected) < 0.01) ? "OK" : "MISMATCH";
            return new ValidationResult("tx_amt", "Settlement Amount", "M", status, String.format("%.2f", actual), String.format("%.2f", expected), remark);

        } catch (Exception e) {
            return new ValidationResult("tx_amt", "Settlement Amount", "M", "ERROR", null, null, "Calc Error: " + e.getMessage());
        }
    }

    private static class NamespaceContextImpl implements javax.xml.namespace.NamespaceContext {
        public String getNamespaceURI(String prefix) {
            if ("header".equals(prefix)) return "urn:iso:std:iso:20022:tech:xsd:head.001.001.03";
            if ("document".equals(prefix)) return "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.10";
            return null;
        }
        public String getPrefix(String uri) { return null; }
        public java.util.Iterator getPrefixes(String uri) { return null; }
    }
}
