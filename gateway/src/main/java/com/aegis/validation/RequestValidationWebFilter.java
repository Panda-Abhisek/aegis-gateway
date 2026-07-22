package com.aegis.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public class RequestValidationWebFilter implements WebFilter {

    private final long maxPayloadSize;
    private final List<String> requiredHeaders;
    private final List<String> allowedContentTypes;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RequestValidationWebFilter(
            @Value("${gateway.validation.max-payload-size:1048576}") long maxPayloadSize,
            @Value("${gateway.validation.required-headers:}") List<String> requiredHeaders,
            @Value("${gateway.validation.allowed-content-types:application/json}") List<String> allowedContentTypes) {
        this.maxPayloadSize = maxPayloadSize;
        this.requiredHeaders = requiredHeaders != null ? requiredHeaders : List.of();
        this.allowedContentTypes = allowedContentTypes != null ? allowedContentTypes : List.of("application/json");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        for (String header : requiredHeaders) {
            if (request.getHeaders().getFirst(header) == null) {
                return error(exchange, HttpStatus.BAD_REQUEST, "Missing required header: " + header);
            }
        }

        HttpMethod method = request.getMethod();
        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            String contentType = request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            if (contentType == null) {
                return error(exchange, HttpStatus.BAD_REQUEST, "Missing Content-Type header");
            }
            if (!isAllowedContentType(contentType)) {
                return error(exchange, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type not allowed: " + contentType);
            }

            long contentLength = request.getHeaders().getContentLength();
            if (contentLength > maxPayloadSize) {
                return error(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "Payload exceeds limit");
            }

            if (isJsonContentType(contentType)) {
                return validateBody(exchange, chain);
            }
        }

        return chain.filter(exchange);
    }

    private Mono<Void> validateBody(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        Flux<DataBuffer> body = request.getBody();

        return DataBufferUtils.join(body)
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    try {
                        objectMapper.readTree(bytes);
                    } catch (Exception e) {
                        return error(exchange, HttpStatus.BAD_REQUEST, "Malformed JSON");
                    }

                    DataBuffer buffer = DefaultDataBufferFactory.sharedInstance.wrap(bytes);
                    ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(buffer);
                        }
                    };
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                });
    }

    private boolean isAllowedContentType(String contentType) {
        for (String allowed : allowedContentTypes) {
            if (contentType.contains(allowed)) {
                return true;
            }
        }
        return false;
    }

    private boolean isJsonContentType(String contentType) {
        return contentType.contains(MediaType.APPLICATION_JSON_VALUE)
                || contentType.contains("json");
    }

    private Mono<Void> error(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        return response.setComplete();
    }
}
