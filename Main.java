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
            for (Object a : arg) {
                System.out.print(a);
                System.out.print(" ");
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
