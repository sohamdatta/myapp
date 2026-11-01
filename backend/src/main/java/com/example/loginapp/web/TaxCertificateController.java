package com.example.loginapp.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.CertificateLayout;
import com.example.loginapp.core.Modules;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.TaxCertificates;

/** Payroll → Tax filing → Certificates (Form 130 Part B), and My pay → Tax certificates. */
@RestController
@RequestMapping("/api/org")
@RequiresModule(Modules.PAYROLL)
public class TaxCertificateController {

    /** Releasing a certificate built on draft tax rules needs the approver's explicit yes. */
    public record ReleaseRequest(boolean acknowledgeDraftRules) {}

    private final TaxCertificates certificates;
    private final CertificateLayout layout;

    public TaxCertificateController(TaxCertificates certificates, CertificateLayout layout) {
        this.certificates = certificates;
        this.layout = layout;
    }

    /** How this Organization's certificate looks: the version in force and the ones before it. */
    @GetMapping("/payroll/certificate-layout")
    public CertificateLayout.View layout(SessionCtx ctx) {
        return layout.get(ctx);
    }

    /** Saves the layout as the next version (an Org Admin). Drafts pick it up when built again; released certificates never change. */
    @PutMapping("/payroll/certificate-layout")
    public CertificateLayout.View saveLayout(SessionCtx ctx, @RequestBody CertificateLayout.Input request) {
        return layout.save(ctx, request);
    }

    /** A sample certificate with made-up figures in the layout typed in the body (or the one in force when empty). Nothing is stored. */
    @PostMapping("/payroll/certificate-layout/preview")
    public ResponseEntity<byte[]> previewLayout(SessionCtx ctx, @RequestBody(required = false) CertificateLayout.Input request) {
        return DocumentController.file(layout.preview(ctx, request));
    }

    @GetMapping("/payroll/tax-certificates")
    public TaxCertificates.Year year(SessionCtx ctx, @RequestParam(value = "taxYear", required = false) String taxYear) {
        return certificates.year(ctx, taxYear);
    }

    @PostMapping("/payroll/tax-certificates/build/{taxYear}")
    public TaxCertificates.BuildResult build(SessionCtx ctx, @PathVariable("taxYear") String taxYear) {
        return certificates.build(ctx, taxYear);
    }

    @PostMapping("/payroll/tax-certificates/{id}/release")
    public TaxCertificates.Row release(SessionCtx ctx, @PathVariable("id") String id, @RequestBody(required = false) ReleaseRequest request) {
        return certificates.release(ctx, Requests.id(id), request != null && request.acknowledgeDraftRules());
    }

    @GetMapping("/payroll/tax-certificates/{id}/pdf")
    public ResponseEntity<byte[]> file(SessionCtx ctx, @PathVariable("id") String id) {
        return DocumentController.file(certificates.file(ctx, Requests.id(id)));
    }

    @GetMapping("/me/tax-certificates")
    public List<TaxCertificates.Mine> mine(SessionCtx ctx) {
        return certificates.mine(ctx);
    }

    @GetMapping("/me/tax-certificates/{id}/pdf")
    public ResponseEntity<byte[]> mineFile(SessionCtx ctx, @PathVariable("id") String id) {
        return DocumentController.file(certificates.mine(ctx, Requests.id(id)));
    }
}
