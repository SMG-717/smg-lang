import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import smg.interpreter.Interpreter;
import smg.interpreter.Interpreter.F0;

public class Main {
    public static void main(String[] args) throws IOException {
        final String code = String.join("\n", Files.readAllLines(Paths.get("./code.smg")));

        final Interpreter interpreter = new Interpreter(code);
        final F0 print = arg -> {
            if (arg.length == 0) return;
            System.out.print(arg[0]);
            for (int i = 1; i < arg.length; i += 1) {
                System.out.print(" ");
                System.out.print(arg[i]);
            }
        };

        final F0 println = arg -> {
            print.apply(arg);
            System.out.println();
        };

        interpreter.addVar("print", print );
        interpreter.addVar("println", println );
        interpreter.interpret();
    }
}
