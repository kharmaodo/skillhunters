package com.skillhunters.documents;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpDocumentAnalyzer implements DocumentAnalyzer {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;
    private final String token;
    private final ObjectMapper json;
    public HttpDocumentAnalyzer(@Value("${app.analysis.endpoint:http://localhost:8090/analyze}") URI endpoint,
            @Value("${app.analysis.token:}") String token, ObjectMapper json) {
        this.endpoint=endpoint; this.token=token; this.json=json;
    }
    public Result analyze(byte[] data, String mediaType, String sha256) throws Exception {
        if(token.length()<32) throw new IllegalStateException("WORKER_UNCONFIGURED");
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(210))
            .header("Authorization","Bearer "+token).header("Content-Type",mediaType)
            .header("X-Content-SHA256",sha256).POST(HttpRequest.BodyPublishers.ofByteArray(data)).build();
        var response=client.send(request, ignored -> new BoundedBody());
        if(response.statusCode()!=200) throw new IllegalStateException("WORKER_UNAVAILABLE");
        return json.readValue(response.body(),Result.class);
    }
    private static class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> future=new CompletableFuture<>();
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return future; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for(var chunk:chunks) {
                if(bytes.size()+chunk.remaining()>4*1024*1024) { subscription.cancel(); future.completeExceptionally(new IllegalStateException("WORKER_RESULT_LIMIT")); return; }
                byte[] block=new byte[chunk.remaining()]; chunk.get(block); bytes.writeBytes(block);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { future.completeExceptionally(error); }
        public void onComplete() { future.complete(bytes.toByteArray()); }
    }
}
