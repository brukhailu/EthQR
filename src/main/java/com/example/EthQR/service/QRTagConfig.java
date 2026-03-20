package com.example.EthQR.service;

import com.example.EthQR.model.TLVTag;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
public class QRTagConfig {

    @Bean
    public Map<String, TLVTag> tlvTags(ObjectMapper objectMapper) throws IOException {
        ClassPathResource resource = new ClassPathResource("qr-tags.json");
        try (InputStream inputStream = resource.getInputStream()) {
            List<TLVTag> tagList = objectMapper.readValue(inputStream, new TypeReference<List<TLVTag>>() {});
            return tagList.stream()
                    .collect(Collectors.toMap(TLVTag::getTag, Function.identity(), (o1, o2) -> o1, TreeMap::new));
        }
    }
}
