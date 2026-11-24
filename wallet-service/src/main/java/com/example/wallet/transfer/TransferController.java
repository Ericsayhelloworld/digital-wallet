package com.example.wallet.transfer;

import com.example.wallet.common.CurrentUser;
import com.example.wallet.idempotency.IdempotencyService;
import com.example.wallet.idempotency.IdempotencyService.StoredResponse;
import com.example.wallet.transfer.TransferService.TransferRequest;
import com.example.wallet.transfer.TransferService.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transfers;
    private final IdempotencyService idempotency;

    public TransferController(TransferService transfers, IdempotencyService idempotency) {
        this.transfers = transfers;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<String> create(@AuthenticationPrincipal Jwt jwt,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @Valid @RequestBody TransferRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return idempotency.execute(user.id(), "transfers", key, request, () -> {
            TransferResponse transfer = transfers.create(user, request);
            return new StoredResponse(201, transfer, "/api/v1/transfers/" + transfer.id()); // API-7
        });
    }

    @GetMapping("/{id}")
    public TransferResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return transfers.get(CurrentUser.from(jwt), id);
    }
}
