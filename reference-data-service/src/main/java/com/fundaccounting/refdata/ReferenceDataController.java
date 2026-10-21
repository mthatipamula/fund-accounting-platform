package com.fundaccounting.refdata;

import com.fundaccounting.common.ApiErrors.ApiException;
import com.fundaccounting.common.AsOf;
import com.fundaccounting.refdata.Model.CorporateActionDto;
import com.fundaccounting.refdata.Model.CorporateActionRow;
import com.fundaccounting.refdata.Model.FxDto;
import com.fundaccounting.refdata.Model.FxRow;
import com.fundaccounting.refdata.Model.PriceDto;
import com.fundaccounting.refdata.Model.PriceRow;
import com.fundaccounting.refdata.Model.SecurityDto;
import com.fundaccounting.refdata.Model.UploadResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reference")
public class ReferenceDataController {
    private static final int MAX_ROWS = 50_000;
    private final ReferenceDataRepository repo;

    public ReferenceDataController(ReferenceDataRepository repo) { this.repo = repo; }

    @GetMapping("/securities")
    List<SecurityDto> securities() { return repo.securities(List.of()); }

    @GetMapping("/securities/{id}")
    SecurityDto security(@PathVariable String id) {
        return repo.securities(List.of(id)).stream().findFirst().orElseThrow(() -> ApiException.notFound("Security " + id));
    }

    @PostMapping("/securities")
    @ResponseStatus(HttpStatus.CREATED)
    SecurityDto createSecurity(@Valid @RequestBody SecurityDto s) {
        repo.createSecurity(s);
        return s;
    }

    @GetMapping("/prices")
    List<PriceRow> prices(@RequestParam(required = false) List<String> securityId, @RequestParam LocalDate from,
                          @RequestParam LocalDate to, @RequestParam(required = false) String knownAt) {
        return repo.prices(securityId == null ? List.of() : securityId, from, to, AsOf.knownAt(knownAt));
    }

    @GetMapping("/prices/{securityId}/{date}/history")
    List<PriceRow> priceHistory(@PathVariable String securityId, @PathVariable LocalDate date) {
        return repo.priceHistory(securityId, date);
    }

    @PutMapping("/prices")
    UploadResult savePrices(@RequestBody List<@Valid PriceDto> prices) {
        if (prices.size() > MAX_ROWS) throw ApiException.badRequest("At most " + MAX_ROWS + " prices per request");
        return new UploadResult(prices.size(), repo.savePrices(prices));
    }

    /** CSV with header: security_id,date,price[,source] */
    @PostMapping("/prices/upload")
    UploadResult uploadPrices(@RequestParam("file") MultipartFile file) throws IOException {
        List<PriceDto> rows = new ArrayList<>();
        for (Map<String, String> r : csv(file, List.of("security_id", "date", "price"))) {
            rows.add(new PriceDto(r.get("security_id"), LocalDate.parse(r.get("date")), new BigDecimal(r.get("price")),
                    r.getOrDefault("source", "UPLOAD")));
        }
        return new UploadResult(rows.size(), repo.savePrices(rows));
    }

    @GetMapping("/fx")
    List<FxRow> fx(@RequestParam(required = false) List<String> currency, @RequestParam(defaultValue = "USD") String base,
                   @RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) String knownAt) {
        return repo.fx(currency == null ? List.of() : currency, base, from, to, AsOf.knownAt(knownAt));
    }

    @PutMapping("/fx")
    UploadResult saveFx(@RequestBody List<@Valid FxDto> rates) {
        if (rates.size() > MAX_ROWS) throw ApiException.badRequest("At most " + MAX_ROWS + " rates per request");
        return new UploadResult(rates.size(), repo.saveFx(rates));
    }

    /** CSV with header: currency,base_currency,date,rate */
    @PostMapping("/fx/upload")
    UploadResult uploadFx(@RequestParam("file") MultipartFile file) throws IOException {
        List<FxDto> rows = new ArrayList<>();
        for (Map<String, String> r : csv(file, List.of("currency", "base_currency", "date", "rate"))) {
            rows.add(new FxDto(r.get("currency"), r.get("base_currency"), LocalDate.parse(r.get("date")), new BigDecimal(r.get("rate"))));
        }
        return new UploadResult(rows.size(), repo.saveFx(rows));
    }

    @GetMapping("/corporate-actions")
    List<CorporateActionRow> corporateActions(@RequestParam(required = false) List<String> securityId) {
        return repo.corporateActions(securityId == null ? List.of() : securityId);
    }

    @PostMapping("/corporate-actions")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> createCorporateAction(@Valid @RequestBody CorporateActionDto ca) {
        return Map.of("id", repo.createCorporateAction(ca));
    }

    @PutMapping("/corporate-actions/{id}")
    void amendCorporateAction(@PathVariable String id, @Valid @RequestBody CorporateActionDto ca) {
        repo.reviseCorporateAction(id, ca, false);
    }

    @PostMapping("/corporate-actions/{id}/cancel")
    void cancelCorporateAction(@PathVariable String id) { repo.reviseCorporateAction(id, null, true); }

    private static List<Map<String, String>> csv(MultipartFile file, List<String> required) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = in.readLine();
            if (headerLine == null) throw ApiException.badRequest("The file is empty");
            String[] header = headerLine.trim().toLowerCase().split(",");
            for (String r : required) {
                if (!List.of(header).contains(r)) throw ApiException.badRequest("Missing column '" + r + "'");
            }
            String line;
            int n = 1;
            while ((line = in.readLine()) != null) {
                n++;
                if (line.isBlank()) continue;
                if (rows.size() >= MAX_ROWS) throw ApiException.badRequest("At most " + MAX_ROWS + " rows per file");
                String[] cells = line.split(",", -1);
                if (cells.length < header.length) throw ApiException.badRequest("Line " + n + " has too few columns");
                Map<String, String> row = new java.util.HashMap<>();
                for (int i = 0; i < header.length; i++) row.put(header[i], cells[i].trim());
                rows.add(row);
            }
        }
        return rows;
    }
}
