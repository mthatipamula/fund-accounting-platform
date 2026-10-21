package com.fundaccounting.trades;

import com.fundaccounting.common.ApiErrors.ApiException;
import com.fundaccounting.common.AsOf;
import com.fundaccounting.trades.TradeDto.Request;
import com.fundaccounting.trades.TradeDto.Revision;
import com.fundaccounting.trades.TradeDto.Version;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trades")
public class TradeController {
    private final TradeService trades;

    public TradeController(TradeService trades) { this.trades = trades; }

    @GetMapping
    List<Version> list(@RequestParam String fundId, @RequestParam(required = false) String knownAt) {
        return trades.asKnownAt(fundId, AsOf.knownAt(knownAt));
    }

    @GetMapping("/{id}/versions")
    List<Version> versions(@PathVariable String id) { return trades.history(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Version create(@Valid @RequestBody Request r, @AuthenticationPrincipal Jwt jwt,
                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKey.length() > 100) throw ApiException.badRequest("Idempotency-Key is too long");
        return trades.create(r, user(jwt), idempotencyKey);
    }

    @PutMapping("/{id}")
    Version amend(@PathVariable String id, @Valid @RequestBody Revision rev, @AuthenticationPrincipal Jwt jwt) {
        if (rev.trade() == null) throw ApiException.badRequest("trade is required");
        return trades.amend(id, rev.expectedVersion(), rev.trade(), user(jwt), rev.reason());
    }

    @PostMapping("/{id}/cancel")
    Version cancel(@PathVariable String id, @Valid @RequestBody Revision rev, @AuthenticationPrincipal Jwt jwt) {
        return trades.cancel(id, rev.expectedVersion(), user(jwt), rev.reason());
    }

    /** CSV: fund_id,security_id,side,quantity,price,commission,trade_date[,settle_date]. All rows or none. */
    @PostMapping("/upload")
    Map<String, Object> upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) throws IOException {
        List<Request> rows = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = in.readLine();
            if (headerLine == null) throw ApiException.badRequest("The file is empty");
            String[] h = headerLine.trim().toLowerCase().split(",");
            String line;
            int n = 1;
            while ((line = in.readLine()) != null) {
                n++;
                if (line.isBlank()) continue;
                if (rows.size() >= 10_000) throw ApiException.badRequest("At most 10,000 trades per file");
                String[] c = line.split(",", -1);
                Map<String, String> r = new HashMap<>();
                for (int i = 0; i < h.length && i < c.length; i++) r.put(h[i], c[i].trim());
                try {
                    rows.add(new Request(r.get("fund_id"), r.get("security_id"), r.get("side").toUpperCase(),
                            new BigDecimal(r.get("quantity")), new BigDecimal(r.get("price")),
                            new BigDecimal(r.getOrDefault("commission", "0").isBlank() ? "0" : r.get("commission")),
                            LocalDate.parse(r.get("trade_date")),
                            r.getOrDefault("settle_date", "").isBlank() ? null : LocalDate.parse(r.get("settle_date")), "Upload"));
                } catch (RuntimeException e) {
                    throw ApiException.badRequest("Line " + n + " is invalid: " + e.getMessage());
                }
            }
        }
        List<String> ids = trades.createAll(rows, user(jwt));
        return Map.of("loaded", ids.size(), "tradeIds", ids);
    }

    private static String user(Jwt jwt) {
        String name = jwt.getClaimAsString("preferred_username");
        return name != null ? name : jwt.getSubject();
    }
}
