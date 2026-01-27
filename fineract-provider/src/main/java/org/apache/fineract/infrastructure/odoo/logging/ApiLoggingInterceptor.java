package org.apache.fineract.infrastructure.odoo.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ApiLoggingInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiLoggingInterceptor.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Response intercept(Chain chain) throws IOException {

        Request request = chain.request();

        // ---- REQUEST BODY ----
        String requestBody = null;
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            requestBody = buffer.readUtf8();
        }

        log.info("REQUEST | {}  {}  {} | {}", DateUtils.getAuditLocalDateTime(), request.method(), request.url(), requestBody);

        long start = System.currentTimeMillis();
        Response response = chain.proceed(request);

        // ---- RESPONSE BODY ----
        ResponseBody body = response.body();
        String responseBody = body != null ? body.string() : null;

        log.info("RESPONSE | {}  {} {} {} | {}", DateUtils.getAuditLocalDateTime(), System.currentTimeMillis() - start, response.code(),
                request.url(), responseBody);

        // IMPORTANT: recreate body
        return response.newBuilder().body(ResponseBody.create(responseBody, body != null ? body.contentType() : null)).build();
    }

    private String pretty(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        try {
            Object json = mapper.readValue(body, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Exception e) {
            return body;
        }
    }
}
