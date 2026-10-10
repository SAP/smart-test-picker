// SPDX-License-Identifier: Apache-2.0
package example.cfsupport;

import com.sap.oss.smarttestpicker.remote.RemoteTestContext;
import example.cf.ObservedStages;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.concurrent.*;

/** No STP wrappers here: observations measure the actual agent-installed context. */
public final class StageCallbacks {
    public static final ContextKey<String> EXTRA = ContextKey.named("fixture-extra");
    public final CompletableFuture<String> inner = new CompletableFuture<>();
    public final CountDownLatch entered = new CountDownLatch(1);
    public volatile String actual;
    public volatile String extra;
    public volatile String trace;
    public volatile long thread;
    public volatile String threadName;
    public volatile int calls;
    public volatile Object input;
    public volatile Throwable error;
    public String leaf = "a";
    public boolean fail;
    public CyclicBarrier barrier;

    private void visit(Object value, Throwable failure) {
        actual = String.valueOf(RemoteTestContext.currentIdentity());
        extra = Context.current().get(EXTRA);
        trace = Span.current().getSpanContext().getTraceId();
        thread = Thread.currentThread().getId();
        threadName = Thread.currentThread().getName();
        input = value;
        error = failure;
        calls++;
        switch (leaf) {
            case "a" -> ObservedStages.a();
            case "b" -> ObservedStages.b();
            case "c" -> ObservedStages.c();
            default -> ObservedStages.anonymous();
        }
        entered.countDown();
        if (barrier != null) try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception e) { throw new AssertionError(e); }
        if (fail) throw new IllegalStateException("callback-failure");
    }
    public String function(String value) { visit(value, null); return value + "-result"; }
    public void consumer(String value) { visit(value, null); }
    public void runnable() { visit(null, null); }
    public CompletionStage<String> compose(String value) { visit(value, null); return inner; }
    public String handle(String value, Throwable failure) { visit(value, failure); return failure == null ? value + "-result" : "recovered"; }
    public void whenComplete(String value, Throwable failure) { visit(value, failure); }
    public String recover(Throwable failure) { visit(null, failure); return "recovered"; }
    public String combine(String a, String b) { visit(a + ":" + b, null); return a + ":" + b + "-result"; }
    public void both(String a, String b) { visit(a + ":" + b, null); }
}
