package org.quarkpress.it.admin;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Test scaffolding, not part of any API: the requests the application has answered, as {@code "<method> <uri>
 * <status>"} in the order their answers ended, for the flows that wait on a request the browser sends while it leaves
 * a page (Chromium sends it after the page is gone, so the browser's tools never report it). The flows run in the
 * application's own JVM and read the log directly.
 */
@ApplicationScoped
public class ItRequestLog {

    private static final BlockingQueue<String> ANSWERED = new LinkedBlockingQueue<>();

    /** Marks a request already logged, so that its re-routed passes are not logged again. */
    private static final String LOGGED = ItRequestLog.class.getName();

    /** Runs before every other filter. */
    void register(@Observes Filters filters) {
        filters.register(this::log, 10_000);
    }

    void log(RoutingContext context) {
        if (context.get(LOGGED) == null) {
            context.put(LOGGED, Boolean.TRUE);
            HttpServerRequest request = context.request();
            String line = request.method().name() + " " + request.uri();
            context.addEndHandler(
                    ended -> ANSWERED.add(line + " " + context.response().getStatusCode()));
        }
        context.next();
    }

    /** Forgets the requests answered so far. */
    public static void clear() {
        ANSWERED.clear();
    }

    /**
     * Waits for the next answered request whose line contains {@code part}, skipping the others, and answers its line;
     * null when none is answered {@code within}.
     */
    public static String await(String part, Duration within) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        while (true) {
            String line = ANSWERED.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (line == null || line.contains(part)) {
                return line;
            }
        }
    }
}
