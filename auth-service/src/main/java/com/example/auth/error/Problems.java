package com.example.auth.error;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** API-6: RFC 9457 problem details with the extra members code and traceId. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail build(HttpStatusCode status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://wallet.example/problems/" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("traceId", UUID.randomUUID().toString().replace("-", ""));
        return problem;
    }

    /** Same content as a plain map, for writing the response by hand in security handlers. */
    public static Map<String, Object> asMap(ProblemDetail problem) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", problem.getType().toString());
        map.put("title", problem.getTitle());
        map.put("status", problem.getStatus());
        map.put("detail", problem.getDetail());
        if (problem.getProperties() != null) {
            map.putAll(problem.getProperties());
        }
        return map;
    }
}
