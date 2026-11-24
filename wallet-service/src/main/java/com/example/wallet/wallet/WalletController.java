package com.example.wallet.wallet;

import com.example.wallet.common.CurrentUser;
import com.example.wallet.common.Pagination;
import com.example.wallet.common.Pagination.Page;
import com.example.wallet.error.ApiException;
import com.example.wallet.ledger.LedgerService;
import com.example.wallet.ledger.LedgerService.EntryResponse;
import com.example.wallet.wallet.WalletService.OpenResult;
import com.example.wallet.wallet.WalletService.WalletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    public record LookupResponse(String walletId, String username, String displayName) {
    }

    private final WalletService walletService;
    private final WalletRepository wallets;
    private final LedgerService ledger;

    public WalletController(WalletService walletService, WalletRepository wallets, LedgerService ledger) {
        this.walletService = walletService;
        this.wallets = wallets;
        this.ledger = ledger;
    }

    /** WAL-1: 201 when created, 200 when it existed. */
    @PostMapping
    public ResponseEntity<WalletResponse> open(@AuthenticationPrincipal Jwt jwt) {
        OpenResult result = walletService.open(CurrentUser.from(jwt));
        return ResponseEntity.status(result.created() ? 201 : 200).body(walletService.toResponse(result.wallet()));
    }

    /** WAL-3 */
    @GetMapping("/me")
    public WalletResponse me(@AuthenticationPrincipal Jwt jwt) {
        return walletService.toResponse(walletService.requireWallet(CurrentUser.from(jwt).id()));
    }

    /** WAL-4: only the wallet ID, the username and the display name. */
    @GetMapping("/lookup")
    public LookupResponse lookup(@RequestParam String username) {
        Wallet wallet = wallets.findByUsername(username).orElseThrow(ApiException::walletNotFound);
        return new LookupResponse(wallet.id().toString(), wallet.username(), wallet.displayName());
    }

    /** WAL-5: the statement, newest first, with cursor pagination. */
    @GetMapping("/me/entries")
    public Page<EntryResponse> entries(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) Integer limit,
                                       @RequestParam(required = false) String cursor) {
        Wallet wallet = walletService.requireWallet(CurrentUser.from(jwt).id());
        return ledger.statement(wallet.id(), type, Pagination.limit(limit), Pagination.decode(cursor));
    }
}
