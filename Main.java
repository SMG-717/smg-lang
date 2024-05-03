import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import smg.interpreter.Interpreter;
import smg.interpreter.Interpreter.F0;

public class Main {
    public static void main(String[] args) throws IOException {
        final String code = String.join("\n", Files.readAllLines(Paths.get("./code.smg")));
        final Interpreter intr = new Interpreter(code);
        intr.addVar("println", (F0) (arg -> { ((F0) intr.getVar("print")).apply(arg); System.out.println(); }));
        intr.addVar("print", (F0) arg -> {
            if (arg.length == 0) return;
            System.out.print(arg[0]);
            for (int i = 1; i < arg.length; i += 1) System.out.print(" " + arg[i]);
        });
        try {
            final Object result = intr.interpret();
            System.out.println(result == null ? "" : result);
        }
        catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }
}
