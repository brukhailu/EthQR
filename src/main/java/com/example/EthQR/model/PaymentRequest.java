package com.example.EthQR.model;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class PaymentRequest {

    // ===== CUSTOMER INFORMATION (from logged-in user profile) =====
    private String customerName;
    private String customerAccountId;
    private String customerMobile;
    private String customerEmail;
    private String customerAddress;

    // ===== PAYMENT INFORMATION =====
    private BigDecimal amount;        // Transaction amount (base + tip)
    private BigDecimal tipAmount;     // Calculated tip amount

    // ===== QR PROMPTED VALUES =====
    // Tag 62/01 - Bill Number (when value is '***')
    private String billNumber;

    // Tag 62/02 - Mobile Number for Top-up (when value is '***')
    private String mobileNumber;

    // Tag 62/09 - Additional Consumer Data Request (A, M, E flags)
    private String consumerAddress;   // When 'A' flag is present
    private String consumerEmail;     // When 'E' flag is present
    private String consumerMobile;    // When 'M' flag is present

    // ===== OPTIONAL OVERRIDES =====
    private String categoryPurpose;   // Override CtgyPurp (C2BSQR, C2BDQR, C2BBPT)
    private String purposeCode;       // Override Purp/Prtry (ONLPUR, SALA, etc.)
    private String remittanceInfo;    // Additional remittance information (Ustrd)

    // ===== RAW QR DATA =====
    private String rawTlvString; // Raw TLV string from the scanned QR code

    // ===== CUSTOMER PROFILE FIELDS (can be overridden by user) =====
    private String debtorName;        // Payer's name (overrides default)
    private String debtorAccountId;   // Payer's account number (overrides default)
    private String debtorMobile;      // Payer's mobile number
    private String debtorEmail;       // Payer's email address
    private String debtorAddress;     // Payer's address

    // ===== CONSTRUCTORS =====
    public PaymentRequest() {}

    // ===== GETTERS AND SETTERS =====

    // Customer Information
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

    // Payment Information
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getTipAmount() { return tipAmount; }
    public void setTipAmount(BigDecimal tipAmount) { this.tipAmount = tipAmount; }

    // QR Prompted Values
    public String getBillNumber() { return billNumber; }
    public void setBillNumber(String billNumber) { this.billNumber = billNumber; }

    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }

    // Consumer Data (Tag 62/09)
    public String getConsumerAddress() { return consumerAddress; }
    public void setConsumerAddress(String consumerAddress) { this.consumerAddress = consumerAddress; }

    public String getConsumerEmail() { return consumerEmail; }
    public void setConsumerEmail(String consumerEmail) { this.consumerEmail = consumerEmail; }

    public String getConsumerMobile() { return consumerMobile; }
    public void setConsumerMobile(String consumerMobile) { this.consumerMobile = consumerMobile; }

    // Optional Overrides
    public String getCategoryPurpose() { return categoryPurpose; }
    public void setCategoryPurpose(String categoryPurpose) { this.categoryPurpose = categoryPurpose; }

    public String getPurposeCode() { return purposeCode; }
    public void setPurposeCode(String purposeCode) { this.purposeCode = purposeCode; }

    public String getRemittanceInfo() { return remittanceInfo; }
    public void setRemittanceInfo(String remittanceInfo) { this.remittanceInfo = remittanceInfo; }

    // Raw TLV String
    public String getRawTlvString() { return rawTlvString; }
    public void setRawTlvString(String rawTlvString) { this.rawTlvString = rawTlvString; }

    // Debtor Profile Fields (for overriding defaults)
    public String getDebtorName() { return debtorName; }
    public void setDebtorName(String debtorName) { this.debtorName = debtorName; }

    public String getDebtorAccountId() { return debtorAccountId; }
    public void setDebtorAccountId(String debtorAccountId) { this.debtorAccountId = debtorAccountId; }

    public String getDebtorMobile() { return debtorMobile; }
    public void setDebtorMobile(String debtorMobile) { this.debtorMobile = debtorMobile; }

    public String getDebtorEmail() { return debtorEmail; }
    public void setDebtorEmail(String debtorEmail) { this.debtorEmail = debtorEmail; }

    public String getDebtorAddress() { return debtorAddress; }
    public void setDebtorAddress(String debtorAddress) { this.debtorAddress = debtorAddress; }

    // ===== HELPER METHODS =====

    /**
     * Checks if additional consumer data is requested (Tag 62/09)
     */
    public boolean isConsumerAddressRequested() {
        return consumerAddress != null && !consumerAddress.isEmpty();
    }

    public boolean isConsumerEmailRequested() {
        return consumerEmail != null && !consumerEmail.isEmpty();
    }

    public boolean isConsumerMobileRequested() {
        return consumerMobile != null && !consumerMobile.isEmpty();
    }

    /**
     * Checks if this is a bill payment
     */
    public boolean isBillPayment() {
        return billNumber != null && !billNumber.isEmpty();
    }

    /**
     * Checks if this is a mobile top-up payment
     */
    public boolean isMobileTopUp() {
        return mobileNumber != null && !mobileNumber.isEmpty();
    }

    /**
     * Gets the total transaction amount (base + tip)
     */
    public BigDecimal getTotalAmount() {
        if (amount == null) return BigDecimal.ZERO;
        if (tipAmount == null) return amount;
        return amount.add(tipAmount);
    }

    /**
     * Converts relevant fields of this PaymentRequest object into a Map<String, String>.
     * This is useful for passing user inputs to services that expect a map.
     * The keys in the map are designed to align with potential QR tag references or common input names.
     * Note: Only non-null and non-empty string values are included. BigDecimal amounts are converted to String.
     *
     * @return A Map<String, String> representing the user inputs.
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();

        // Customer Information
        Optional.ofNullable(customerName).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("customerName", s));
        Optional.ofNullable(customerAccountId).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("customerAccountId", s));
        Optional.ofNullable(customerMobile).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("customerMobile", s));
        Optional.ofNullable(customerEmail).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("customerEmail", s));
        Optional.ofNullable(customerAddress).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("customerAddress", s));

        // Payment Information
        Optional.ofNullable(amount).ifPresent(bd -> map.put("amount", bd.toPlainString()));
        Optional.ofNullable(tipAmount).ifPresent(bd -> map.put("tipAmount", bd.toPlainString()));

        // QR Prompted Values (aligned with common user input keys)
        Optional.ofNullable(billNumber).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("62_01", s)); // Tag 62.01
        Optional.ofNullable(mobileNumber).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("62_02", s)); // Tag 62.02

        // Consumer Data (Tag 62/09)
        Optional.ofNullable(consumerAddress).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("consumerAddress", s));
        Optional.ofNullable(consumerEmail).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("consumerEmail", s));
        Optional.ofNullable(consumerMobile).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("consumerMobile", s));

        // Optional Overrides
        Optional.ofNullable(categoryPurpose).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("categoryPurpose", s));
        Optional.ofNullable(purposeCode).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("purposeCode", s));
        Optional.ofNullable(remittanceInfo).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("remittanceInfo", s));

        // Raw TLV String
        Optional.ofNullable(rawTlvString).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("rawTlvString", s));

        // Debtor Profile Fields
        Optional.ofNullable(debtorName).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("dbtr_nm", s));
        Optional.ofNullable(debtorAccountId).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("dbtr_acct_id", s));
        Optional.ofNullable(debtorMobile).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("dbtr_mob", s));
        Optional.ofNullable(debtorEmail).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("dbtr_email", s));
        Optional.ofNullable(debtorAddress).filter(s -> !s.isEmpty()).ifPresent(s -> map.put("dbtr_addr", s));

        return map;
    }


    // ===== BUILDER PATTERN =====
    public static class Builder {
        private PaymentRequest request = new PaymentRequest();

        // Customer Information
        public Builder customerName(String name) {
            request.customerName = name;
            return this;
        }

        public Builder customerAccountId(String id) {
            request.customerAccountId = id;
            return this;
        }

        public Builder customerMobile(String mobile) {
            request.customerMobile = mobile;
            return this;
        }

        public Builder customerEmail(String email) {
            request.customerEmail = email;
            return this;
        }

        public Builder customerAddress(String address) {
            request.customerAddress = address;
            return this;
        }

        // Payment Information
        public Builder amount(BigDecimal amount) {
            request.amount = amount;
            return this;
        }

        public Builder tipAmount(BigDecimal tip) {
            request.tipAmount = tip;
            return this;
        }

        // QR Prompted Values
        public Builder billNumber(String number) {
            request.billNumber = number;
            return this;
        }

        public Builder mobileNumber(String number) {
            request.mobileNumber = number;
            return this;
        }

        // Consumer Data
        public Builder consumerAddress(String address) {
            request.consumerAddress = address;
            return this;
        }

        public Builder consumerEmail(String email) {
            request.consumerEmail = email;
            return this;
        }

        public Builder consumerMobile(String mobile) {
            request.consumerMobile = mobile;
            return this;
        }

        // Optional Overrides
        public Builder categoryPurpose(String purpose) {
            request.categoryPurpose = purpose;
            return this;
        }

        public Builder purposeCode(String code) {
            request.purposeCode = code;
            return this;
        }

        public Builder remittanceInfo(String info) {
            request.remittanceInfo = info;
            return this;
        }

        // Raw TLV String
        public Builder rawTlvString(String rawTlvString) {
            request.rawTlvString = rawTlvString;
            return this;
        }

        // Debtor Profile Fields
        public Builder debtorName(String name) {
            request.debtorName = name;
            return this;
        }

        public Builder debtorAccountId(String id) {
            request.debtorAccountId = id;
            return this;
        }

        public Builder debtorMobile(String mobile) {
            request.debtorMobile = mobile;
            return this;
        }

        public Builder debtorEmail(String email) {
            request.debtorEmail = email;
            return this;
        }

        public Builder debtorAddress(String address) {
            request.debtorAddress = address;
            return this;
        }

        public PaymentRequest build() {
            return request;
        }
    }

    // ===== TOSTRING METHOD FOR DEBUGGING =====
    @Override
    public String toString() {
        return "PaymentRequest{" +
                "customerName='" + customerName + '\'' +
                ", customerAccountId='" + customerAccountId + '\'' +
                ", amount=" + amount +
                ", tipAmount=" + tipAmount +
                ", totalAmount=" + getTotalAmount() +
                ", billNumber='" + billNumber + '\'' +
                ", mobileNumber='" + mobileNumber + '\'' +
                ", consumerAddress='" + consumerAddress + '\'' +
                ", consumerEmail='" + consumerEmail + '\'' +
                ", consumerMobile='" + consumerMobile + '\'' +
                ", categoryPurpose='" + categoryPurpose + '\'' +
                ", purposeCode='" + purposeCode + '\'' +
                ", remittanceInfo='" + remittanceInfo + '\'' +
                ", rawTlvString='" + rawTlvString + '\'' +
                '}';
    }
}
