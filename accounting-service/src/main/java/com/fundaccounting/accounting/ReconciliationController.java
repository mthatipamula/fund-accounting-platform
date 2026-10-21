package com.fundaccounting.accounting;

import com.fundaccounting.accounting.ValuationService.Valuation;
import com.fundaccounting.common.ApiErrors.ApiException;
import com.fundaccounting.common.AsOf;
import com.fundaccounting.engine.FundState;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reconciles the books against a custodian or prime broker statement.
 *
 * <p>CSV columns: {@code type,id,quantity} where type is POSITION (id = security id, quantity = units) or CASH
 * (id = currency, quantity = local balance). Items within the tolerance match; the rest are breaks.
 */
@RestController
@RequestMapping("/api/accounting/funds/{fundId}/reconciliation")
public class ReconciliationController {
    private final ValuationService valuation;

    public ReconciliationController(ValuationService valuation) { this.valuation = valuation; }

    public record Line(String type, String id, BigDecimal books, BigDecimal statement, BigDecimal difference, String status) {}

    public record Result(String fundId, String asOf, int matched, int breaks, List<Line> lines) {}

    @PostMapping
    public Result reconcile(@PathVariable String fundId, @RequestParam String asOf, @RequestParam("file") MultipartFile file,
                            @RequestParam(defaultValue = "0.01") BigDecimal tolerance) throws IOException {
        Valuation v = valuation.value(fundId, AsOf.date(asOf), null);
        Map<String, BigDecimal> books = new TreeMap<>();
        for (FundState.Position p : v.state().positions()) books.put("POSITION|" + p.securityId(), p.quantity());
        for (FundState.CashBalance c : v.state().cash()) books.put("CASH|" + c.currency(), c.local());

        Map<String, BigDecimal> statement = new TreeMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = in.readLine()) != null) {
                n++;
                if (line.isBlank() || (n == 1 && line.toLowerCase().startsWith("type"))) continue;
                String[] f = line.split(",");
                if (f.length != 3) throw ApiException.badRequest("Line " + n + ": expected type,id,quantity");
                String type = f[0].trim().toUpperCase();
                if (!type.equals("POSITION") && !type.equals("CASH")) throw ApiException.badRequest("Line " + n + ": type must be POSITION or CASH");
                try {
                    statement.merge(type + "|" + f[1].trim(), new BigDecimal(f[2].trim()), BigDecimal::add);
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("Line " + n + ": quantity is not a number");
                }
            }
        }

        List<Line> lines = new ArrayList<>();
        int matched = 0;
        TreeSet<String> keys = new TreeSet<>(books.keySet());
        keys.addAll(statement.keySet());
        for (String key : keys) {
            String[] k = key.split("\\|");
            BigDecimal b = books.getOrDefault(key, BigDecimal.ZERO), s = statement.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal diff = b.subtract(s);
            String status = diff.abs().compareTo(tolerance) <= 0 ? "MATCHED"
                    : !books.containsKey(key) ? "MISSING_IN_BOOKS" : !statement.containsKey(key) ? "MISSING_IN_STATEMENT" : "BREAK";
            if (status.equals("MATCHED")) matched++;
            lines.add(new Line(k[0], k[1], b, s, diff, status));
        }
        return new Result(fundId, v.state().asOf().toString(), matched, lines.size() - matched, lines);
    }
}
