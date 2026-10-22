package com.skillhunters.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpEmbeddings implements EmbeddingPort {
    private record Response(String model,List<List<Double>> vectors) {}
    private final ObjectMapper json;private final URI endpoint;private final String token;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public HttpEmbeddings(ObjectMapper json,@Value("${app.embeddings.endpoint:http://localhost:8091/embed}") URI endpoint,@Value("${app.embeddings.token:}") String token) {
        this.json=json;this.endpoint=endpoint;this.token=token;
    }
    public List<List<Double>> encode(List<String> labels) throws Exception {
        if(token.length()<32) throw new IllegalStateException("EMBEDDINGS_UNCONFIGURED");
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token)
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("texts",labels)))).build();
        var response=client.send(request, ignored -> new BoundedBody());
        if(response.statusCode()!=200) throw new IllegalStateException("EMBEDDINGS_UNAVAILABLE");
        var result=json.readValue(response.body(),Response.class);
        if(!MODEL.equals(result.model())) throw new IllegalStateException("EMBEDDINGS_VERSION");
        return result.vectors();
    }
    private static class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> future=new CompletableFuture<>();
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() {return future;}
        public void onSubscribe(Flow.Subscription subscription) {this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> chunks) {
            for(var chunk:chunks) {
                if(bytes.size()+chunk.remaining()>2*1024*1024) {subscription.cancel();future.completeExceptionally(new IllegalStateException("EMBEDDINGS_LIMIT"));return;}
                byte[] block=new byte[chunk.remaining()];chunk.get(block);bytes.writeBytes(block);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) {future.completeExceptionally(error);}
        public void onComplete() {future.complete(bytes.toByteArray());}
    }
}
