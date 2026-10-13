package demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.jaegertracing.Configuration;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;

/** Java 8 demo app instrumented with the Jaeger client (OpenTracing API). Config comes from JAEGER_* env vars. */
public class App {
    private static final Random RND = new Random();
    private static Tracer tracer;

    public static void main(String[] args) throws IOException {
        tracer = Configuration.fromEnv("java8-demo").getTracer();
        GlobalTracer.registerIfAbsent(tracer);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() { tracer.close(); }
        }));

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/hello", handler("GET /hello", App::hello));
        server.createContext("/work", handler("GET /work", App::work));
        server.createContext("/error", handler("GET /error", App::fail));
        server.start();
        System.out.println("Listening on :8080 (/hello, /work, /error)");
    }

    interface Action { String run() throws Exception; }

    private static HttpHandler handler(final String name, final Action action) {
        return new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                Span span = tracer.buildSpan(name)
                        .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                        .withTag(Tags.HTTP_METHOD.getKey(), ex.getRequestMethod())
                        .withTag(Tags.HTTP_URL.getKey(), ex.getRequestURI().toString())
                        .start();
                int code = 200;
                String body;
                try (Scope ignored = tracer.activateSpan(span)) {
                    body = action.run();
                } catch (Exception e) {
                    code = 500;
                    body = "error: " + e.getMessage();
                    Tags.ERROR.set(span, true);
                    Map<String, Object> fields = new HashMap<>();
                    fields.put("event", "error");
                    fields.put("error.object", e);
                    fields.put("message", String.valueOf(e.getMessage()));
                    span.log(fields);
                }
                span.setTag(Tags.HTTP_STATUS.getKey(), code);
                span.finish();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(code, bytes.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
            }
        };
    }

    private static String hello() { return "hello from java8\n"; }

    private static String work() throws InterruptedException {
        String user = queryDatabase();
        callDownstream(user);
        return "done for " + user + "\n";
    }

    private static String queryDatabase() throws InterruptedException {
        Span span = tracer.buildSpan("db.query users").start();
        try (Scope ignored = tracer.activateSpan(span)) {
            span.setTag("db.system", "demo");
            span.setTag("db.statement", "SELECT name FROM users WHERE id = ?");
            Thread.sleep(20 + RND.nextInt(80));
            return "user-" + RND.nextInt(100);
        } finally {
            span.finish();
        }
    }

    private static void callDownstream(String user) throws InterruptedException {
        Span span = tracer.buildSpan("downstream.call").start();
        try (Scope ignored = tracer.activateSpan(span)) {
            span.setTag("user", user);
            Thread.sleep(50 + RND.nextInt(150));
            span.log("downstream responded");
        } finally {
            span.finish();
        }
    }

    private static String fail() {
        Span span = tracer.buildSpan("risky.operation").start();
        try (Scope ignored = tracer.activateSpan(span)) {
            throw new IllegalStateException("simulated failure");
        } catch (RuntimeException e) {
            Tags.ERROR.set(span, true);
            span.log(Collections.singletonMap("error.object", e));
            throw e;
        } finally {
            span.finish();
        }
    }
}
