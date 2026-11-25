package com.example.wallet.admin;

import com.example.wallet.admin.AdminService.IntegrityReport;
import com.example.wallet.admin.AdminService.Status;
import com.example.wallet.admin.AdminService.WalletRow;
import com.example.wallet.common.Pagination;
import com.example.wallet.common.Pagination.Page;
import com.example.wallet.operation.Reconciler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Section 6.4. Only the role ADMIN reaches these endpoints (SEC-3, see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService admin;
    private final Reconciler reconciler;

    public AdminController(AdminService admin, Reconciler reconciler) {
        this.admin = admin;
        this.reconciler = reconciler;
    }

    /** ADM-1 */
    @GetMapping("/integrity")
    public IntegrityReport integrity() {
        return admin.integrity();
    }

    /** ADM-2 */
    @GetMapping("/status")
    public Status status() {
        return admin.status();
    }

    /** ADM-3 */
    @GetMapping("/wallets")
    public Page<WalletRow> wallets(@RequestParam(required = false) Integer limit,
                                   @RequestParam(required = false) String cursor) {
        return admin.wallets(Pagination.limit(limit), Pagination.decode(cursor));
    }

    /** ADM-4: 202, the run continues in the background */
    @PostMapping("/reconciliation")
    public ResponseEntity<Map<String, String>> reconcile() {
        reconciler.startNow();
        return ResponseEntity.accepted().body(Map.of("status", "STARTED"));
    }
}
