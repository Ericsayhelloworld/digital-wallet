package com.example.wallet.operation;

import com.example.wallet.common.CurrentUser;
import com.example.wallet.common.Pagination.Page;
import com.example.wallet.idempotency.IdempotencyService;
import com.example.wallet.idempotency.IdempotencyService.StoredResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class OperationController {

    public record AmountRequest(@NotNull Long amount) {
    }

    private final OperationService service;
    private final IdempotencyService idempotency;

    public OperationController(OperationService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    // ---------- deposits ----------

    @PostMapping("/deposits")
    public ResponseEntity<String> createDeposit(@AuthenticationPrincipal Jwt jwt,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                @Valid @RequestBody AmountRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return idempotency.execute(user.id(), "deposits", key, request, () -> {
            Operation.Response deposit = service.createDeposit(user, request.amount());
            return new StoredResponse(202, deposit, "/api/v1/deposits/" + deposit.id());
        });
    }

    @GetMapping("/deposits/{id}")
    public Operation.Response getDeposit(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.get(CurrentUser.from(jwt), OperationKind.DEPOSIT, id);
    }

    @GetMapping("/deposits")
    public Page<Operation.Response> listDeposits(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) Integer limit,
                                                 @RequestParam(required = false) String cursor) {
        return service.list(CurrentUser.from(jwt), OperationKind.DEPOSIT, status, limit, cursor);
    }

    // ---------- withdrawals ----------

    @PostMapping("/withdrawals")
    public ResponseEntity<String> createWithdrawal(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                   @Valid @RequestBody AmountRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return idempotency.execute(user.id(), "withdrawals", key, request, () -> {
            Operation.Response withdrawal = service.createWithdrawal(user, request.amount());
            return new StoredResponse(202, withdrawal, "/api/v1/withdrawals/" + withdrawal.id());
        });
    }

    @GetMapping("/withdrawals/{id}")
    public Operation.Response getWithdrawal(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.get(CurrentUser.from(jwt), OperationKind.WITHDRAWAL, id);
    }

    @GetMapping("/withdrawals")
    public Page<Operation.Response> listWithdrawals(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Integer limit,
                                                    @RequestParam(required = false) String cursor) {
        return service.list(CurrentUser.from(jwt), OperationKind.WITHDRAWAL, status, limit, cursor);
    }
}
