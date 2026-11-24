package com.example.wallet.operation;

import com.example.wallet.bank.BankTransaction;
import com.example.wallet.bank.WebhookSignature;
import com.example.wallet.common.Ids;
import com.example.wallet.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** POST /internal/webhooks/bank: the bank reports the result of a transaction (Section 6.6). */
@RestController
public class BankWebhookController {

    private final WebhookSignature signature;
    private final OperationService service;
    private final ObjectMapper objectMapper;

    public BankWebhookController(WebhookSignature signature, OperationService service, ObjectMapper objectMapper) {
        this.signature = signature;
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/internal/webhooks/bank")
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Bank-Timestamp", required = false) String timestamp,
                                        @RequestHeader(value = "X-Bank-Signature", required = false) String sig,
                                        @RequestBody String rawBody) {
        // The signature covers the raw body, so we verify it BEFORE parsing the JSON
        signature.verify(timestamp, sig, rawBody);

        BankTransaction transaction;
        try {
            transaction = objectMapper.readValue(rawBody, BankTransaction.class);
        } catch (Exception e) {
            throw ApiException.validation("body", "is not a bank transaction");
        }
        OperationKind kind = OperationKind.fromBankType(transaction.type());
        UUID id = Ids.parse(transaction.reference()).orElseThrow(ApiException::notFound);
        if (kind == null) {
            throw ApiException.notFound();
        }
        service.applyResult(kind, id, transaction.status(), transaction.reason());
        return ResponseEntity.ok().build();
    }
}
