package com.example.EthQR.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class TLVTag {
    private String tag;
    private String name;

    @JsonProperty("isSubTLV")
    private boolean isSubTLV;

    private List<TLVTag> subTags; // For nested sub-tags
    private String dataField; // To map to QRCodeData fields
    private boolean container; // If true, the dataField is a Map containing this tag
    private String delimiter; // For string values that need to be split into sub-tags
    private String dataType; // e.g., "ANS" (Alphanumeric Special), "N" (Numeric), "S" (String)
    private Integer minLength;
    private Integer maxLength;
    private String pattern; // Regex pattern for validation
    private boolean mandatory; // Is this tag mandatory
    private List<Option> options; // For predefined values
    private String uiType; // e.g. "checkbox" for multi-select
    private String isoPath; // ISO 20022 mapping path
    private String isoExample; // Real-world XML example for mapping

    public static class Option {
        private String value;
        private String label;

        public Option() {}
        public Option(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    public TLVTag() {
        // No-arg constructor for Jackson
    }

    public TLVTag(String tag, String name, boolean isSubTLV, List<TLVTag> subTags, String dataField, boolean container, String delimiter, String dataType, Integer minLength, Integer maxLength, String pattern, boolean mandatory, List<Option> options, String uiType, String isoPath, String isoExample) {
        this.tag = tag;
        this.name = name;
        this.isSubTLV = isSubTLV;
        this.subTags = subTags;
        this.dataField = dataField;
        this.container = container;
        this.delimiter = delimiter;
        this.dataType = dataType;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.pattern = pattern;
        this.mandatory = mandatory;
        this.options = options;
        this.uiType = uiType;
        this.isoPath = isoPath;
        this.isoExample = isoExample;
    }

    // Getters and Setters
    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isSubTLV() {
        return isSubTLV;
    }

    public void setSubTLV(boolean subTLV) {
        isSubTLV = subTLV;
    }

    public List<TLVTag> getSubTags() {
        return subTags;
    }

    public void setSubTags(List<TLVTag> subTags) {
        this.subTags = subTags;
    }

    public String getDataField() {
        return dataField;
    }

    public void setDataField(String dataField) {
        this.dataField = dataField;
    }

    public boolean isContainer() {
        return container;
    }

    public void setContainer(boolean container) {
        this.container = container;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(String delimiter) {
        this.delimiter = delimiter;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public Integer getMinLength() {
        return minLength;
    }

    public void setMinLength(Integer minLength) {
        this.minLength = minLength;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(Integer maxLength) {
        this.maxLength = maxLength;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public List<Option> getOptions() {
        return options;
    }

    public void setOptions(List<Option> options) {
        this.options = options;
    }

    public String getUiType() {
        return uiType;
    }

    public void setUiType(String uiType) {
        this.uiType = uiType;
    }

    public String getIsoPath() {
        return isoPath;
    }

    public void setIsoPath(String isoPath) {
        this.isoPath = isoPath;
    }

    public String getIsoExample() {
        return isoExample;
    }

    public void setIsoExample(String isoExample) {
        this.isoExample = isoExample;
    }
}
