package com.example.loginapp.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.DocumentService;
import com.example.loginapp.core.FileSniffer;

/**
 * Documents. An upload is the file itself as the request body (its type is
 * read from its bytes, never from the header), with the file name and the
 * document type in the query string. Files are sent back as attachments,
 * never to be rendered by the browser as a page.
 */
@RestController
@RequestMapping("/api/org")
public class DocumentController {

    public record Note(String note) {}

    private final AuthService auth;
    private final DocumentService documents;

    public DocumentController(AuthService auth, DocumentService documents) {
        this.auth = auth;
        this.documents = documents;
    }

    @GetMapping("/onboarding/me/documents")
    public Map<String, Object> mine(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return documents.mine(Requests.session(auth, authorization));
    }

    @PostMapping("/onboarding/me/documents")
    public Map<String, Object> uploadMine(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestParam(value = "type", required = false) String type,
                                          @RequestParam(value = "name", required = false) String name, InputStream body) {
        var ctx = Requests.session(auth, authorization);
        return documents.uploadMine(ctx, type, name, read(body));
    }

    @GetMapping("/onboarding/me/documents/{id}/content")
    public ResponseEntity<byte[]> myContent(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable("id") String id) {
        return file(documents.myContent(Requests.session(auth, authorization), Requests.id(id)));
    }

    @GetMapping("/people/joiners/{id}/documents")
    public Map<String, Object> forJoiner(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable("id") String id) {
        return documents.forJoiner(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/people/joiners/{id}/documents")
    public Map<String, Object> uploadForJoiner(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable("id") String id,
                                               @RequestParam(value = "type", required = false) String type,
                                               @RequestParam(value = "name", required = false) String name, InputStream body) {
        var ctx = Requests.session(auth, authorization);
        return documents.uploadForJoiner(ctx, Requests.id(id), type, name, read(body));
    }

    @PostMapping("/people/joiners/{id}/documents/{documentId}/verify")
    public Map<String, Object> verify(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id, @PathVariable("documentId") String documentId) {
        return documents.verify(Requests.session(auth, authorization), Requests.id(id), Requests.id(documentId));
    }

    @PostMapping("/people/joiners/{id}/documents/{documentId}/reject")
    public Map<String, Object> reject(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id, @PathVariable("documentId") String documentId,
                                      @RequestBody Note request) {
        return documents.reject(Requests.session(auth, authorization), Requests.id(id), Requests.id(documentId), request.note());
    }

    @GetMapping("/people/documents/{id}/content")
    public ResponseEntity<byte[]> content(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable("id") String id) {
        return file(documents.content(Requests.session(auth, authorization), Requests.id(id)));
    }

    /** Reads at most one byte more than allowed, so an oversized upload is refused without reading it all. */
    static byte[] read(InputStream body) {
        try {
            byte[] bytes = body == null ? new byte[0] : body.readNBytes(FileSniffer.MAX_BYTES + 1);
            if (bytes.length > FileSniffer.MAX_BYTES) {
                throw new ApiException(413, "document.too_large", "The file is larger than 10 MB. Send a smaller photo or a PDF");
            }
            return bytes;
        } catch (IOException e) {
            throw ApiException.badRequest("The upload was interrupted. Try again");
        }
    }

    static ResponseEntity<byte[]> file(DocumentService.Content content) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, content.contentType());
        // The name was made safe on upload: letters, digits, spaces, dots, dashes and underscores only.
        headers.set("Content-Disposition", "attachment; filename=\"" + content.fileName() + "\"");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "no-store");
        return new ResponseEntity<>(content.bytes(), headers, HttpStatus.OK);
    }
}
