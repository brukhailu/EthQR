package com.example.EthQR.model;

import java.util.Map;

public class ValidationRequest {
    private String xml;
    private Map<String, Object> qrData;
    private Map<String, String> userInputs;
    private String scenario;

    // Getters and Setters
    public String getXml() {
        return xml;
    }

    public void setXml(String xml) {
        this.xml = xml;
    }

    public Map<String, Object> getQrData() {
        return qrData;
    }

    public void setQrData(Map<String, Object> qrData) {
        this.qrData = qrData;
    }

    public Map<String, String> getUserInputs() {
        return userInputs;
    }

    public void setUserInputs(Map<String, String> userInputs) {
        this.userInputs = userInputs;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }
}
