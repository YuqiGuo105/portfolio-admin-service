package site.yuqi.admin.operations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Establishes stable request correlation without placing user content in telemetry. */
@Component
public class OperationContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        OperationContext context = OperationContext.from(request);
        OperationContext.set(context);
        response.setHeader("X-Correlation-Id", context.correlationId());
        try {
            filterChain.doFilter(request, response);
        } finally {
            OperationContext.clear();
        }
    }
}
