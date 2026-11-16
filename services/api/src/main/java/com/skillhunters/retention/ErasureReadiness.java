package com.skillhunters.retention;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.actuate.health.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ErasureReadiness extends OncePerRequestFilter implements HealthIndicator {
    private final RetentionGuard guard;
    public ErasureReadiness(RetentionGuard guard){this.guard=guard;}
    public Health health(){return guard.ready()?Health.up().build():Health.down().withDetail("reason","ERASURE_REPLAY_REQUIRED").build();}
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
        if(!guard.ready()&&!req.getRequestURI().startsWith("/actuator/health")){res.setStatus(503);res.setHeader("Cache-Control","no-store");res.setContentType("application/problem+json");res.getWriter().write("{\"status\":503,\"code\":\"ERASURE_REPLAY_REQUIRED\"}");return;}chain.doFilter(req,res);
    }
}
