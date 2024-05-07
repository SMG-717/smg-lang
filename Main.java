import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;

import smg.interpreter.Interpreter;
import smg.interpreter.Capture.F;

public class Main {
    public static void main(String[] args) throws IOException {
        final String code = String.join("\n", Files.readAllLines(Paths.get("./code.smg")));
        final Interpreter intr = new Interpreter(code);

        final F
        println = arg -> { ((F) intr.getVar("print")).apply(arg); System.out.println(); return null; },
        print = arg -> {
            if (arg.length != 0) {
                System.out.print(arg[0]);
                for (int i = 1; i < arg.length; i += 1) System.out.print(" " + arg[i]);
            }
            return null;
        },
        bigd = a -> {
            if (a[0] instanceof Double) {
                return BigDecimal.valueOf((Double) a[0]);
            }
            return a[0];
        },
        integer = a -> ((Number) a[0]).intValue();

        intr.defineVar("println", println);
        intr.defineVar("print", print);
        intr.defineVar("bigd", bigd);
        intr.defineVar("integer", integer);

        intr.setBigDecimalMode(false);

        try {
            final Object result = intr.run();
            System.out.println(result == null ? "" : result);
        }
        catch (Exception e) {
            System.out.println(red(e.getMessage()));
        }

        System.out.println(((Number) 1l).doubleValue() == 1.0D);
    }

    private final static String ANSI_RED = "\033[0;31m"; 
    private final static String ANSI_RESET = "\033[0m"; 
    private static String red(String text) {
        return ANSI_RED + text + ANSI_RESET;
    }
}
