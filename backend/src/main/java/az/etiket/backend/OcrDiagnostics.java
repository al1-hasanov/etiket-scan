package az.etiket.backend;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded operational diagnostics. Only numbers and short controlled codes belong in these events. */
@Component
final class OcrDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(OcrDiagnostics.class);
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final int MAX_EVENTS = 250;
    private final boolean enabled;

    OcrDiagnostics(@Value("${ocr.diagnostics:false}") boolean enabled) {
        this.enabled = enabled;
        LOG.info("OCR_DIAGNOSTICS enabled={}", enabled);
    }

    Scope job(String id, String role, String language, int attempt) {
        Context previous = CURRENT.get();
        if (enabled) CURRENT.set(new Context(id, role, language, attempt));
        else CURRENT.remove();
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }

    static Pass pass(String variant, int psm, int threshold, int rotation, int width, int height) {
        Context context = CURRENT.get();
        if (context == null) return new Pass(null, "", 0);
        String previous = context.pass;
        context.pass = "p" + ++context.passes;
        event("pass_started", "variant", variant, "psm", psm, "threshold", threshold, "rotation", rotation, "width", width, "height", height);
        return new Pass(context, previous, System.nanoTime());
    }

    static void event(String name, Object... fields) {
        Context context = CURRENT.get();
        if (context == null) return;
        boolean terminal = name.equals("job_result") || name.equals("job_failed");
        if (!terminal && context.events++ >= MAX_EVENTS) {
            if (context.events == MAX_EVENTS + 1)
                LOG.info("OCR_DIAGNOSTIC jobId={} event=detail_limit maxEvents={}", context.id, MAX_EVENTS);
            return;
        }
        StringBuilder data = new StringBuilder();
        for (int i = 0; i + 1 < Math.min(fields.length, 24); i += 2)
            data.append(' ').append(code(fields[i])).append('=').append(code(fields[i + 1]));
        LOG.info("OCR_DIAGNOSTIC jobId={} role={} language={} attempt={} pass={} event={} elapsedMs={}{}",
            context.id, context.role, context.language, context.attempt, context.pass, code(name),
            (System.nanoTime() - context.started) / 1_000_000, data);
    }

    private static String code(Object value) {
        if (value instanceof Double number) return Double.isFinite(number) ? String.format(Locale.ROOT, "%.3f", number) : "NA";
        String text = String.valueOf(value);
        return text.matches("[A-Za-z0-9_.:-]{1,80}") ? text : "REDACTED";
    }

    interface Scope extends AutoCloseable { @Override void close(); }

    static final class Pass implements AutoCloseable {
        private final Context context;
        private final String previous;
        private final long started;
        private Pass(Context context, String previous, long started) {
            this.context = context; this.previous = previous; this.started = started;
        }
        @Override public void close() {
            if (context != null) {
                event("pass_finished", "durationMs", (System.nanoTime() - started) / 1_000_000);
                context.pass = previous;
            }
        }
    }

    private static final class Context {
        private final String id, role, language;
        private final int attempt;
        private final long started = System.nanoTime();
        private String pass = "none";
        private int events, passes;
        private Context(String id, String role, String language, int attempt) {
            this.id = code(id); this.role = code(role); this.language = code(language); this.attempt = attempt;
        }
    }
}
