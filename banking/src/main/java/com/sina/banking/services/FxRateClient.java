package com.sina.banking.services;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service 
public class FxRateClient {

    private RestClient restClient;

    public record FxRateResponse(
        String from,
        String to,
        double rate
    ) {}

    public FxRateClient(@Value("${fx.service.url}") String fxServiceUrl) {
        this.restClient =  RestClient.create(fxServiceUrl);
    }

    public BigDecimal getRate(String from, String to) {

        FxRateResponse response = restClient.get().uri("/rate?from=" + from + "&to=" + to)
                .retrieve().body(FxRateResponse.class);

        return BigDecimal.valueOf(response.rate);
    }
}


