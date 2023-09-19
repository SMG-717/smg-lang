package interpreter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) {

        final String file = "code.smg";

        String code = null;
        try {
            code = String.join("\n", Files.readAllLines(Paths.get(file)));
        } catch (IOException e) {}

        final List<List<Object>> expressions = List.of(

            // Test cases
            List.of("true", true),
            List.of("false", false),
            List.of("not(true)", false),
            List.of("not(false)", true),
            List.of("true and true", true),
            List.of("true and false", false),
            List.of("false or true", true),
            List.of("false or false", false),
            List.of("1 == 1", true),
            List.of("1 != 0", true),
            List.of("1 - 1 == 0", true),
            List.of("1 + 2 == 3", true),
            List.of("49 / 7 == 7", true),
            List.of("2 ^ 3 ^ 4 == 2417851639229258349412352", true),
            List.of("2 ^ 3 ^ 4 != 4096", true),
            List.of("2 * 3 == 6", true),
            List.of("3 ^ 4 == 81", true),
            List.of("3 ^ 4 * 2 + 1 == 163", true),
            List.of("1 + 2 * 3 ^ 4 == 163", true),
            List.of("1 + 3 ^ 4 * 2 ^ 3 == 649", true),
            List.of("2 - 2 == empty", true),
            List.of("empty or true", ERROR),
            List.of("true or empty", true),

            // Varibles
            List.of("x > 5", true),
            List.of("y <= 8", true),
            List.of("SMG == 'Best'", true),
            List.of("Others != 'Best'", true),
            List.of("Everyone == 'Best'", ERROR),
            List.of("Today < 01/01/2030", true),

            // Operator Precedence
            List.of("(1 + 2 + 3) * 100 * 4 / (30 + 5 ^ 2 - 5) == 24 * 2", true),
            List.of("120 / 5 / 4 / 3 / 2 == 1", true),

            // Unbalanced Brackets
            List.of("(true)", true),
            List.of("(true", ERROR),
            List.of("true)", ERROR),
            List.of("((((true)))))", ERROR),
            List.of("((((true))))", true),
            List.of("((((true))) or true)", true),

            // Simple atomic expressions must evaluate to boolean
            List.of("0", 0.0D),
            List.of("SMG", "Best"),
            List.of("1 + 2", 3.0D),
            List.of("Active", true),

            // Code from file
            List.of(code, true)
        );

        final Map<String, Object> vars = Map.of(
            "x", 10, 
            "y", 7, 
            "SMG", "Best", 
            "Others", "Not Best", 
            "Active", true,
            "Today", Date.from(Instant.now()),
            "Sqrt2", Math.sqrt(2)
        );

        int index = 0, count = 0;
        StringBuilder fails = new StringBuilder();
        for (List<Object> test : expressions) {
            final String expression = (String) test.get(0);
            boolean success = false; 
            Interpreter intr = null;
            
            try {
                intr = new Interpreter(expression, vars);
                success = intr.interpret().equals(test.get(1)) && test.get(1) != ERROR;
            } catch (Exception e) {
                success = test.get(1) == ERROR;
            }

            count += success ? 1 : 0;
            final String testResult = String.format("  Test %2d: %s -> %s\n", 
                ++index, 
                expression == code ? "[Code from file: " + file + "]" : expression,
                (success ? green("PASS") : red("FAIL"))
            );
            if (!success) 
                fails.append(testResult);
            // System.out.println("Tree -> " + (intr.getTree() != null ? intr.getTree() : "None"));
        }
        final String marks = String.format("%d/%d", count, expressions.size());
        System.out.println("Total tests passed: " + (count == expressions.size() ? green(marks) : red(marks)));
        if (!fails.isEmpty()) {
            System.out.println("Failed cases: ");
            System.out.print(fails.toString());
        }

    }

    private static final Object ERROR = new Object(); 
    
    public static String red(String message) { return String.format("\033[1;31m%s\033[1;0m", message); }    
    public static String green(String message) { return String.format("\033[1;32m%s\033[1;0m", message); }
}
