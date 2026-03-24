package com.example.EthQR.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TransactionLogger {

    private final Map<String, List<String>> logs = new ConcurrentHashMap<>();

    public void log(String transactionId, String message) {
        logs.computeIfAbsent(transactionId, k -> new ArrayList<>()).add(message);
    }

    public List<String> getLogs(String transactionId) {
        return logs.getOrDefault(transactionId, Collections.emptyList());
    }

    public void clear(String transactionId) {
        logs.remove(transactionId);
    }
}
