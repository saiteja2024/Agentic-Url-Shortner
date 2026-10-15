package io.agentic.shortener.web;

import io.agentic.shortener.domain.ShortUrl;
import io.agentic.shortener.generated.api.RedirectApi;
import io.agentic.shortener.service.ShortUrlService;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** GET /{code}: 302 to the long URL (SHORT-006) and records the click (SHORT-007). */
@RestController
class RedirectController implements RedirectApi {

    private final ShortUrlService service;

    RedirectController(ShortUrlService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<Void> redirect(String code, String referer) {
        ShortUrl url = service.resolveAndRecordClick(code, referer);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(url.longUrl()))
                // no-store: every visit must reach the service to be counted.
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
