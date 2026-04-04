package com.trackam.service;

import com.trackam.config.AppProperties;
import com.trackam.exception.TrackAmException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fetches FX rates via the fawazahmed0 currency API (CDN-hosted, free, no API key).
 * Covers 150+ currencies including GHS, NGN, KES, ZAR, UGX and all African currencies.
 *
 * API format:
 *   Latest:     {baseUrl}@latest/v1/currencies/{from}.json
 *   Historical: {baseUrl}@{YYYY-MM-DD}/v1/currencies/{from}.json
 * Response: { "date": "2026-04-03", "{from}": { "{to}": rate } }
 *
 * Historical rates are cached indefinitely — they never change.
 * Latest rates are not cached since they update daily.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeRateService {

    private final RestTemplate restTemplate;
    private final AppProperties props;

    // Historical rates are immutable — safe to cache for the JVM lifetime.
    // Bounded to 5,000 entries (covers ~25 currency pairs × 200 unique dates).
    private final Map<String, BigDecimal> historicalCache = Collections.synchronizedMap(
        new LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BigDecimal> eldest) {
                return size() > 5_000;
            }
        }
    );

    public record ExchangeResult(
        BigDecimal rate,
        BigDecimal convertedAmount,
        String fromCurrency,
        String toCurrency,
        String rateDate
    ) {}

    /**
     * Converts {@code amount} from {@code from} to {@code to} using the historical rate
     * for {@code txDate}. Falls back to the latest rate if historical lookup fails.
     *
     * @return null if currencies are equal or the API fails entirely
     */
    public ExchangeResult convert(BigDecimal amount, String from, String to, String txDate) {
        if (from == null || to == null || from.equalsIgnoreCase(to)) return null;

        String fromLower = from.toLowerCase();
        String toLower = to.toLowerCase();
        String date = txDate != null && txDate.length() >= 10 ? txDate.substring(0, 10) : "latest";
        boolean isHistorical = !"latest".equals(date);

        String cacheKey = fromLower + "-" + toLower + "-" + date;

        if (isHistorical) {
            BigDecimal cachedRate = historicalCache.get(cacheKey);
            if (cachedRate != null) {
                return new ExchangeResult(cachedRate, convert(amount, cachedRate), from, to, date);
            }
        }

        // Historical: {baseUrl}@{date}/v1/currencies/{from}.json
        // Latest:     {baseUrl}@latest/v1/currencies/{from}.json
        String dateTag = isHistorical ? date : "latest";
        String url = props.getExchangeBaseUrl() + "@" + dateTag + "/v1/currencies/" + fromLower + ".json";

        try {
            Map<?, ?> response = restTemplate.getForObject(url, Map.class);
            BigDecimal rate = extractRate(response, fromLower, toLower);
            if (isHistorical) {
                historicalCache.put(cacheKey, rate);
            }
            return new ExchangeResult(rate, convert(amount, rate), from, to, date);
        } catch (Exception e) {
            log.warn("FX lookup failed ({} → {}, {}): {}. Trying latest.", from, to, date, e.getMessage());
        }

        // Fallback to latest rate
        String latestUrl = props.getExchangeBaseUrl() + "@latest/v1/currencies/" + fromLower + ".json";
        try {
            Map<?, ?> response = restTemplate.getForObject(latestUrl, Map.class);
            BigDecimal rate = extractRate(response, fromLower, toLower);
            return new ExchangeResult(rate, convert(amount, rate), from, to, "latest");
        } catch (Exception e) {
            log.warn("FX conversion failed completely ({} → {}): {}", from, to, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private BigDecimal extractRate(Map<?, ?> response, String fromCurrency, String targetCurrency) {
        if (response == null) throw new TrackAmException("Empty FX response");
        // Response: { "date": "...", "{from}": { "{to}": 15.4, ... } }
        Map<String, Object> rates = (Map<String, Object>) response.get(fromCurrency);
        if (rates == null) throw new TrackAmException("No rates for currency: " + fromCurrency);
        Object rate = rates.get(targetCurrency);
        if (rate == null) throw new TrackAmException("Currency not supported: " + targetCurrency);
        return new BigDecimal(rate.toString());
    }

    private static BigDecimal convert(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate, MathContext.DECIMAL128).setScale(2, RoundingMode.HALF_UP);
    }
}
