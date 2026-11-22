package com.smartchef.api.ratelimit;

import com.smartchef.api.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Reune los frenos de los endpoints publicos. */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final String GLOBAL_KEY = "global";

    private final boolean enabled;
    private final ClientIpResolver ipResolver;
    private final FixedWindowRateLimiter aiPerIp;
    private final FixedWindowRateLimiter aiGlobal;
    private final FixedWindowRateLimiter loginPerIp;
    private final FixedWindowRateLimiter loginFailuresPerAccount;
    private final FixedWindowRateLimiter registerPerIp;

    @Autowired
    public RateLimitService(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RateLimitService(RateLimitProperties properties, Clock clock) {
        this.enabled = properties.enabled();
        this.ipResolver = new ClientIpResolver(properties.clientIpHeader());
        this.aiPerIp = limiter(properties.aiPerIp(), clock);
        this.aiGlobal = limiter(properties.aiGlobal(), clock);
        this.loginPerIp = limiter(properties.loginPerIp(), clock);
        this.loginFailuresPerAccount = limiter(properties.loginFailuresPerAccount(), clock);
        this.registerPerIp = limiter(properties.registerPerIp(), clock);
    }

    private static FixedWindowRateLimiter limiter(RateLimitProperties.Limit limit, Clock clock) {
        return new FixedWindowRateLimiter(limit.max(), limit.window(), clock);
    }

    /**
     * Cada llamada a la IA gasta cuota de Gemini: primero el cupo por IP y
     * despues el global, para que un solo cliente no consuma el de todos.
     */
    public void acquireAi(HttpServletRequest request) {
        if (!enabled) {
            return;
        }
        String ip = ipResolver.resolve(request);
        reject(aiPerIp.tryAcquire(ip), "Has hecho demasiadas consultas a la IA. Espera un poco.", "ia/ip", ip);
        reject(aiGlobal.tryAcquire(GLOBAL_KEY),
                "La IA alcanzo su cupo por hoy. Vuelve a intentarlo mas tarde.", "ia/global", ip);
    }

    public void acquireRegister(HttpServletRequest request) {
        if (!enabled) {
            return;
        }
        String ip = ipResolver.resolve(request);
        reject(registerPerIp.tryAcquire(ip), "Demasiados registros desde tu red. Espera un poco.", "registro", ip);
    }

    /** Antes de comprobar la contrasena: cupo por IP y bloqueo por cuenta. */
    public void checkLogin(HttpServletRequest request, String account) {
        if (!enabled) {
            return;
        }
        String ip = ipResolver.resolve(request);
        reject(loginPerIp.tryAcquire(ip), "Demasiados intentos de inicio de sesion. Espera un poco.", "login/ip", ip);
        reject(loginFailuresPerAccount.check(accountKey(account)),
                "Demasiados intentos fallidos para esta cuenta. Espera un poco.", "login/cuenta", ip);
    }

    public void recordLoginFailure(String account) {
        if (enabled) {
            loginFailuresPerAccount.tryAcquire(accountKey(account));
        }
    }

    public void recordLoginSuccess(String account) {
        if (enabled) {
            loginFailuresPerAccount.reset(accountKey(account));
        }
    }

    private static String accountKey(String account) {
        return account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
    }

    private static void reject(FixedWindowRateLimiter.Decision decision, String message, String limit, String ip) {
        if (!decision.allowed()) {
            log.warn("Freno {} activado para {}", limit, ip);
            throw new TooManyRequestsException(message, decision.retryAfterSeconds());
        }
    }
}
