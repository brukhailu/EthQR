package com.example.EthQR.service;

import java.util.List;

public class ValidationRule {
    private String id;
    private String name;
    private String xpath;
    private String type; // M for Mandatory, O for Optional
    private String expected;
    private String desc;
    private String qrTag;
    private QrSubTag qrSubTag;
    private List<String> any;
    private String scenarioKey; // Key in standard-scenarios.json 'data' map

    public static class QrSubTag {
        private String parent;
        private String tag;
        private String contains;

        public String getParent() { return parent; }
        public void setParent(String parent) { this.parent = parent; }
        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }
        public String getContains() { return contains; }
        public void setContains(String contains) { this.contains = contains; }
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getXpath() { return xpath; }
    public void setXpath(String xpath) { this.xpath = xpath; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }
    public String getDesc() { return desc; }
    public void setDesc(String desc) { this.desc = desc; }
    public String getQrTag() { return qrTag; }
    public void setQrTag(String qrTag) { this.qrTag = qrTag; }
    public QrSubTag getQrSubTag() { return qrSubTag; }
    public void setQrSubTag(QrSubTag qrSubTag) { this.qrSubTag = qrSubTag; }
    public List<String> getAny() { return any; }
    public void setAny(List<String> any) { this.any = any; }
    public String getScenarioKey() { return scenarioKey; }
    public void setScenarioKey(String scenarioKey) { this.scenarioKey = scenarioKey; }
}
