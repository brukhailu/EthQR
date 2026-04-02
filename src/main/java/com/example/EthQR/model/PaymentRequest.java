package com.example.EthQR.model;

import java.math.BigDecimal;

public class PaymentRequest {
    // Customer information
    private String customerName;
    private String customerAccountId;
    private String customerMobile;
    private String customerEmail;
    private String customerAddress;

    // Payment information
    private BigDecimal amount;
    private BigDecimal tipAmount;

    // Bill payment information
    private String billNumber;
    private String mobileNumber;

    // Optional overrides
    private String categoryPurpose;
    private String purposeCode;
    private String remittanceInfo;

    // Consumer data (for additional consumer data request)
    private String consumerAddress;
    private String consumerEmail;
    private String consumerMobile;

    // Getters and Setters
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerAccountId() { return customerAccountId; }
    public void setCustomerAccountId(String customerAccountId) { this.customerAccountId = customerAccountId; }

    public String getCustomerMobile() { return customerMobile; }
    public void setCustomerMobile(String customerMobile) { this.customerMobile = customerMobile; }

    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }

    public String getCustomerAddress() { return customerAddress; }
    public void setCustomerAddress(String customerAddress) { this.customerAddress = customerAddress; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getTipAmount() { return tipAmount; }
    public void setTipAmount(BigDecimal tipAmount) { this.tipAmount = tipAmount; }

    public String getBillNumber() { return billNumber; }
    public void setBillNumber(String billNumber) { this.billNumber = billNumber; }

    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }

    public String getCategoryPurpose() { return categoryPurpose; }
    public void setCategoryPurpose(String categoryPurpose) { this.categoryPurpose = categoryPurpose; }

    public String getPurposeCode() { return purposeCode; }
    public void setPurposeCode(String purposeCode) { this.purposeCode = purposeCode; }

    public String getRemittanceInfo() { return remittanceInfo; }
    public void setRemittanceInfo(String remittanceInfo) { this.remittanceInfo = remittanceInfo; }

    public String getConsumerAddress() { return consumerAddress; }
    public void setConsumerAddress(String consumerAddress) { this.consumerAddress = consumerAddress; }

    public String getConsumerEmail() { return consumerEmail; }
    public void setConsumerEmail(String consumerEmail) { this.consumerEmail = consumerEmail; }

    public String getConsumerMobile() { return consumerMobile; }
    public void setConsumerMobile(String consumerMobile) { this.consumerMobile = consumerMobile; }

    // Builder pattern
    public static class Builder {
        private PaymentRequest request = new PaymentRequest();

        public Builder customerName(String name) { request.customerName = name; return this; }
        public Builder customerAccountId(String id) { request.customerAccountId = id; return this; }
        public Builder customerMobile(String mobile) { request.customerMobile = mobile; return this; }
        public Builder customerEmail(String email) { request.customerEmail = email; return this; }
        public Builder customerAddress(String address) { request.customerAddress = address; return this; }
        public Builder amount(BigDecimal amount) { request.amount = amount; return this; }
        public Builder tipAmount(BigDecimal tip) { request.tipAmount = tip; return this; }
        public Builder billNumber(String number) { request.billNumber = number; return this; }
        public Builder mobileNumber(String number) { request.mobileNumber = number; return this; }
        public Builder categoryPurpose(String purpose) { request.categoryPurpose = purpose; return this; }
        public Builder purposeCode(String code) { request.purposeCode = code; return this; }
        public Builder remittanceInfo(String info) { request.remittanceInfo = info; return this; }
        public Builder consumerAddress(String address) { request.consumerAddress = address; return this; }
        public Builder consumerEmail(String email) { request.consumerEmail = email; return this; }
        public Builder consumerMobile(String mobile) { request.consumerMobile = mobile; return this; }

        public PaymentRequest build() { return request; }
    }
}