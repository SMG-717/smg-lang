package interpreter;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import interpreter.NodeComp.Binary;
import interpreter.NodeComp.Term;
import interpreter.NodeExpression.And;
import interpreter.NodeExpression.Comp;
import interpreter.NodeExpression.Not;
import interpreter.NodeExpression.Or;
import interpreter.NodeTerm.Arithmetic;
import interpreter.NodeTerm.Literal;
import interpreter.NodeTerm.Variable;

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

    /***************************************************************************
     * Interpreter
     * 
     * Traverses the Syntax Tree in in-order fashion. Calls the parser if no root
     * node can be found.
     **************************************************************************/
    public boolean interpret() {
        if (parser.getRoot() == null)
            parser.parse();
        return interpretBexpr(parser.getRoot());
    }

    /**
     * Interpret Boolean Expressrion node.
     * 
     * Since all nodes directly, or indirectly extend BExpr, they get redirected here to their respective interpreters.
     * This interpreter also benefits from Java's inherent evaluation system, where if an expression such as 'true or x'
     * which would normally produce an error if 'x' cannot be evaluated as a boolean would actually evaluate to true.
     * This can be beneficial but potentially hard to debug.
     */

    final NodeExpression.Visitor nodeExpressionVisitor = new NodeExpression.Visitor() {
        public boolean visit(And node) { return interpretBexpr(node.lhs) && interpretBexpr(node.rhs); }
        public boolean visit(Or node) { return interpretBexpr(node.lhs) || interpretBexpr(node.rhs); }
        public boolean visit(Not node) { return !interpretBexpr(node.val); }
        public boolean visit(Comp node) { return interpretComp(node.val); }
    };

    private boolean interpretBexpr(NodeExpression node) {
        return node.host(nodeExpressionVisitor);
    }

    /**
     * Interpret Comparision node.
     * 
     * Comparision nodes compare values on both sides of an operator. For a value to be comparable it must first be 
     * represented as a double.
     */

    final NodeComp.Visitor compVisitor = new NodeComp.Visitor() {
        public boolean visit(Term node) {
            final Object value = interpretTerm(node.val);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            throw new RuntimeException("Atomic expression required to be boolean but is not: " + value);
        }

        public boolean visit(Binary node) {
            final double lhs = toDouble(interpretTerm(node.lhs));
            final double rhs = toDouble(interpretTerm(node.rhs));

            switch (node.op) {
                case Equal: return lhs == rhs;
                case NotEqual: return lhs != rhs;
                case GreaterThan: return lhs > rhs;
                case GreaterThanEqual: return lhs >= rhs;
                case LessThan: return lhs < rhs;
                case LessThanEqual: return lhs <= rhs;
                default: 
                    throw new RuntimeException("Unimplemented Comparison Operation");
            }
        }
    };

    private boolean interpretComp(NodeComp comp) { return comp.host(compVisitor); }
    
    /**
     * Interpret Term
     * 
     * Term is an atomic node and is the terminal node in any syntax tree branch. Terms can be literal values or variables
     * that can be provided on Parser creation. Terms can be any value of any type, so long as they fit in higher level 
     * expressions. Qualifier-member nodes will be concatenated with a period "." when grabbed fromn the variable map
     */

    final NodeTerm.Visitor termVisitor = new NodeTerm.Visitor() {
        public Object visit(Literal literal) {
            return literal.lit.val;
        }

        public Object visit(Variable variable) {
            return getVariable(variable.var.name);
        }

        public Object visit(Arithmetic arithmetic) {
            final double lhs = toDouble(interpretTerm(arithmetic.lhs));
            final double rhs = toDouble(interpretTerm(arithmetic.rhs));

            switch (arithmetic.op) {
                case Add: return lhs + rhs;
                case Subtract: return lhs - rhs;
                case Multiply: return lhs * rhs;
                case Divide: return lhs / rhs;
                case Modulus: return lhs % rhs;
                case Exponent: return Math.pow(lhs, rhs);
                default:
                    throw new RuntimeException("Unsupported operation: " + arithmetic.op.name());
            }
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
