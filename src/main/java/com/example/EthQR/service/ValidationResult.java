package com.example.EthQR.service;

public class ValidationResult {
    private String ruleId;
    private String ruleName;
    private String type; // M for Mandatory, O for Optional
    private String status; // PASS, FAIL, MISSING, OPTIONAL_ABSENT
    private String actualValue;
    private String expectedValue;
    private String remarks;

    public ValidationResult(String ruleId, String ruleName, String type, String status, String actualValue, String expectedValue, String remarks) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.type = type;
        this.status = status;
        this.actualValue = actualValue;
        this.expectedValue = expectedValue;
        this.remarks = remarks;
    }

    // Getters
    public String getRuleId() {
        return ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public String getActualValue() {
        return actualValue;
    }

    public String getExpectedValue() {
        return expectedValue;
    }

    public String getRemarks() {
        return remarks;
    }

    // Setters (if needed, but for immutable results, they might not be)
    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setActualValue(String actualValue) {
        this.actualValue = actualValue;
    }

    public void setExpectedValue(String expectedValue) {
        this.expectedValue = expectedValue;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}
