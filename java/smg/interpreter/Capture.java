package smg.interpreter;

import static smg.interpreter.Types.javaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * Simple function captures. Used for named functions and lambdas alike.
 */
public class Capture {
    public final Map<String, Object> variables;
    private final Object function;

    public Capture(List<Map<String, Object>> stack, Object f) {
        variables = new HashMap<>();
        for (Map<String, Object> map : stack) variables.putAll(map);

        if ((function = f) == null) throw new IllegalArgumentException(
            "Supplying null for function is not allowed"
        );
    }

    public Object invoke(Interpreter intr, Object... args) {
        if (function instanceof F) return ((F) function).apply(args);
        else if (function instanceof F0) ((F0) function).apply(args);
        else throw intr.error(
            "Unsupported function type: " + javaType(function)
        );
        
        return null;
    }

    @FunctionalInterface
    public static interface F { public Object apply(Object... args); }
    
    @FunctionalInterface
    public static interface F0 { public void apply(Object... args); }
}
