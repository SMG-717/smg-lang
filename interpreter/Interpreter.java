package interpreter;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import interpreter.NodeExpression.Binary;
import interpreter.NodeExpression.Term;
import interpreter.NodeExpression.Unary;
import interpreter.NodeStatement.Assignment;
import interpreter.NodeStatement.Expression;

public class Interpreter {

    /**
     * Parser for a small custom language.
     * 
     * The process is split into three interleaving parts, namely:
     *   - Tokeniser
     *   - Parser
     *   - Interpreter
     * 
     * Although the Tokeniser is independent from the Parser, it works with the
     * latter in tandem to minimise space and resources used.
     */


    private final Map<String, Object> variables;
    private final Parser parser;
    private Object lastResult;

    public Interpreter(String input) {
        this(input, new HashMap<>());
    }
    
    public Interpreter(String input, Map<String, Object> variables) {
        this.parser = new Parser(input);
        this.variables = variables;
    }

    /**
     * Variable management.
     * 
     * Crucial for the execution of the interpreter when variables are involved.
     */
    public Interpreter addVariable(String key, Object value) {
        variables.put(key, value);
        return this;
    }

    public Object getVariable(String key) {

        if (!variables.containsKey(key)) {
            throw new RuntimeException("Variable " + key + " is undefined");
        }
        return variables.get(key);
    }

    public Interpreter clearVariables() {
        variables.clear();
        return this;
    }

    public String getTree() {
        return parser.getRoot() != null ? parser.getRoot().toString() : null;
    }

    /***************************************************************************
     * Interpreter
     * 
     * Traverses the Syntax Tree in in-order fashion. Calls the parser if no root
     * node can be found.
     **************************************************************************/
    public Object interpret() {
        if (parser.getRoot() == null)
            parser.parse();
        return interpretProgram(parser.getRoot());
    }

    public Object interpretProgram(NodeProgram program) {
        for (NodeStatement statement : program.statements) {
            lastResult = interpretStatement(statement);
        }

        return lastResult;
    }

    private final NodeStatement.Visitor statementVisitor = new NodeStatement.Visitor() {

        public Object visit(Assignment assignment) {
            throw new UnsupportedOperationException("Unimplemented method 'visit'");
        }

        public Object visit(Expression expression) {
            return interpretBexpr(expression.expression);
        }
    };

    public Object interpretStatement(NodeStatement statement) {
        return statement.host(statementVisitor);
    }

    /**
     * Interpret Boolean Expressrion node.
     * 
     * Since all nodes directly, or indirectly extend BExpr, they get redirected here to their respective interpreters.
     * This interpreter also benefits from Java's inherent evaluation system, where if an expression such as 'true or x'
     * which would normally produce an error if 'x' cannot be evaluated as a boolean would actually evaluate to true.
     * This can be beneficial but potentially hard to debug.
     */

    final NodeExpression.Visitor<Object> nodeExpressionVisitor = new NodeExpression.Visitor<Object>() {
        @Override
        public Object visit(Binary node) {

            final Object left = interpretBexpr(node.lhs);
            final Object right = interpretBexpr(node.rhs);

            switch (node.op) {
                case Exponent:          return Math.pow(toDouble(left), toDouble(right));
                case Multiply:          return toDouble(left) * toDouble(right);
                case Divide:            return toDouble(left) / toDouble(right);
                case Modulo:            return toDouble(left) % toDouble(right);
                case Add:               return toDouble(left) + toDouble(right);
                case Subtract:          return toDouble(left) - toDouble(right);
                case Greater:           return toDouble(left) > toDouble(right);
                case GreaterEqual:      return toDouble(left) >= toDouble(right);
                case Less:              return toDouble(left) < toDouble(right);
                case LessEqual:         return toDouble(left) <= toDouble(right);
                case NotEqual:          return toDouble(left) != toDouble(right);
                case Equal:             return toDouble(left) == toDouble(right);
                case BitAnd:            return (Integer) left & (Integer) right;
                case BitOr:             return (Integer) left | (Integer) right;
                case BitXor:            return (Integer) left ^ (Integer) right;
                case ShiftLeft:         return (Integer) left << (Integer) right;
                case ShiftRight:        return (Integer) left >> (Integer) right;
                case And:               return (Boolean) left && (Boolean) right;
                case Or:                return (Boolean) left || (Boolean) right;
                default:
                    throw new RuntimeException("Unsupported operation: " + node.op);
            }
        }

        @Override
        public Object visit(Unary node) {
            switch (node.op) {
                case Not:               return ! (Boolean) interpretBexpr(node.val);
                case Invert:            return ~ (Integer) interpretBexpr(node.val);
                case Negate:            return - toDouble(interpretBexpr(node.val));
                case Decrement:
                case Increment:
                default:
                    throw new RuntimeException("Unsupported operation: " + node.op);

            }
        }
        @Override
        public Object visit(Term node) {
            return interpretTerm(node.val);
        }
    };

    private Object interpretBexpr(NodeExpression node) {
        return node.host(nodeExpressionVisitor);
    }

    /**
     * Interpret Comparision node.
     * 
     * Comparision nodes compare values on both sides of an operator. For a value to be comparable it must first be 
     * represented as a double.
     */
    
    /**
     * Interpret Term
     * 
     * Term is an atomic node and is the terminal node in any syntax tree branch. Terms can be literal values or variables
     * that can be provided on Parser creation. Terms can be any value of any type, so long as they fit in higher level 
     * expressions. Qualifier-member nodes will be concatenated with a period "." when grabbed fromn the variable map
     */

    final NodeTerm.Visitor termVisitor = new NodeTerm.Visitor() {
        public Object visit(NodeTerm.Literal literal) {
            return literal.lit.val;
        }

        public Object visit(NodeTerm.Variable variable) {
            return getVariable(variable.var.name);
        }
    };

    private Object interpretTerm(NodeTerm term) { return term.host(termVisitor); }
    
    /**
     * To Double.
     * 
     * For a node to viably exist in an equality, or an arithmetic expression, it must have a numerical representation.
     * For this reason, all values are converted and cast into double. Strings are hashed before being evaluated. While
     * it can lead to bizzare results with most inequality operations, it is "good enough" testing if two strings are the 
     * same. All strings that are equal must have the same hash but not all strings with the same has must be equivalent.
     * Read the Java Documentation on Strings for more info.
     */
    private double toDouble(Object value) {
        if (value == null) return 0;
        else if (value instanceof Date) return ((Date) value).getTime();
        else if (value instanceof Double) return (double) value;
        else if (value instanceof Integer) return (double) (int) value;
        else if (value instanceof String) return (double) ((String) value).hashCode();
        
        throw new RuntimeException("Atomic expression required to be double, or double similar, but is not: " + value);
    }
}
