package com.spotpulse.pagination;

import com.spotpulse.dto.OffsetPageDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PaginationSupport {
    public static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 20;

    private PaginationSupport() {}

    private static boolean isOffsetRequest(HttpServletRequest request) {
        return request.getParameterMap().containsKey("skip") || request.getParameterMap().containsKey("limit");
    }

    public static Pageable pageable(HttpServletRequest request, Pageable original) {
        Sort sort = original.getSort();
        if (sort.getOrderFor("id") == null) sort = sort.and(Sort.by("id"));
        if (!isOffsetRequest(request)) {
            return PageRequest.of(original.getPageNumber(), original.getPageSize(), sort);
        }
        // Offset parameters take precedence over page/size when both interfaces are supplied.
        int skip = parameter(request, "skip", 0);
        int limit = parameter(request, "limit", DEFAULT_LIMIT);
        if (skip < 0 || limit <= 0 || limit > MAX_LIMIT) {
            throw badRequest("skip must be >= 0 and limit must be between 1 and " + MAX_LIMIT);
        }
        return new OffsetPageable(skip, limit, sort);
    }

    private static int parameter(HttpServletRequest request, String name, int fallback) {
        String[] values = request.getParameterValues(name);
        if (values == null) return fallback;
        try {
            if (values.length != 1) throw new NumberFormatException();
            return Integer.parseInt(values[0]);
        } catch (NumberFormatException ex) {
            throw badRequest(name + " must be a single 32-bit integer");
        }
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    public static <T> Object response(Page<T> page, HttpServletRequest request) {
        if (!isOffsetRequest(request)) return page;
        long skip = page.getPageable().getOffset();
        int limit = page.getSize();
        long next = skip + limit;
        String nextLink = null;
        if (next < page.getTotalElements()) {
            // Rebuild from decoded servlet parameters to preserve repeated sort values and encode once.
            UriComponentsBuilder link = UriComponentsBuilder.fromPath(request.getRequestURI());
            Map<String, Object> variables = new LinkedHashMap<>();
            request.getParameterMap().forEach((name, values) -> {
                if (!name.equals("skip") && !name.equals("limit") && !name.equals("page") && !name.equals("size") && !name.equals("_csrf")) {
                    for (String value : values) {
                        String variable = "value" + variables.size();
                        variables.put(variable, value);
                        link.queryParam(name, "{" + variable + "}");
                    }
                }
            });
            nextLink = link.queryParam("skip", next).queryParam("limit", limit)
                    .encode().buildAndExpand(variables).toUriString();
        }
        return new OffsetPageDto<>(page.getContent(), skip, limit, page.getTotalElements(), nextLink);
    }
}
