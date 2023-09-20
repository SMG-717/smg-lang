package interpreter;

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;

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


    private final LinkedList<Map<String, Object>> scopes;
    private final Parser parser;
    private Object lastResult;
    private boolean returnFlag = false;

    public Interpreter(String input) {
        this(input, new HashMap<>());
    }
    
    public Interpreter(String input, Map<String, Object> variables) {
        this.parser = new Parser(input);
        this.scopes = new LinkedList<>();
        scopes.add(new HashMap<>(variables));
    }

    /**
     * Variable management.
     * 
     * Crucial for the execution of the interpreter when variables are involved.
     */
    public Interpreter addVariable(String key, Object value) {
        // variables.put(key, value);

        final Map<String, Object> scope = findVariable(key);

        if (scope != null) {
            scope.put(key, value);
        } else {
            scopes.getLast().put(key, value);
        }
        return this;
    }

    public Object getVariable(String key) {
        final Map<String, Object> scope = findVariable(key);
        if (scope == null) {
            throw new RuntimeException("Variable " + key + " is undefined");
        }
        else {
            return scope.get(key);
        }   
    }

    public boolean defined(String key) {
        return findVariable(key) != null;
    }

    public Map<String, Object> findVariable(String key) {

        Iterator<Map<String, Object>> itr = scopes.descendingIterator();
        while (itr.hasNext()) {
            final Map<String, Object> scope = itr.next();
            if (scope.containsKey(key)) {
                return scope;
            }
        }
        return null;
    }

    public Object getLastResult() {
        return lastResult;
    }

    private void enterScope() {
        scopes.add(new HashMap<>());
    }

    private void exitScope() {
        scopes.removeLast();
    }

    public Interpreter clearVariables() {
        scopes.clear();
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
        return lastResult = interpretScope(parser.getRoot());
    }

    public Object interpretScope(NodeScope scope) {

        enterScope();
        Object output = null;
        for (NodeStatement statement : scope.statements) {
            output = interpretStatement(statement);
            if (returnFlag) {
                break;
            }
        }
        exitScope();

        return output;
    }

    private final NodeStatement.Visitor statementVisitor = new NodeStatement.Visitor() {
        public Object visit(NodeStatement.Assign assignment) {
            if (!defined(assignment.qualifier.name)) {
                throw new RuntimeException("Assignment to an undefined variable: " + assignment.qualifier.name);
            }

            final Object value = interpretExpression(assignment.expression);
            addVariable(assignment.qualifier.name, value);
            return value;
        }

        public Object visit(NodeStatement.Declare declaration) {

            if (defined(declaration.qualifier.name)) {
                throw new RuntimeException("Redefining an existing variable: " + declaration.qualifier.name);
            }
            
            final Object value = interpretExpression(declaration.expression);
            addVariable(declaration.qualifier.name, value);
            return value;
        }

        public Object visit(NodeStatement.Expression expression) {
            return interpretExpression(expression.expression);
        }

        public Object visit(NodeStatement.If ifStmt) {
            if ((Boolean) interpretExpression(ifStmt.expression)) {
                return interpretScope(ifStmt.success);
            }
            else if (ifStmt.fail != null) {
                return interpretScope(ifStmt.fail);
            }
            else {
                return null;
            }
        }
        
        public Object visit(NodeStatement.While whileStmt) {
            while ((Boolean) interpretExpression(whileStmt.expression)) {
                interpretScope(whileStmt.scope);
            }
            return null;
        }
        
        public Object visit(NodeStatement.Scope scope) {
            return interpretScope(scope.scope);
        }

        public Object visit(NodeStatement.Function function) {
            if (defined(function.name.name + "()")) {
                throw new RuntimeException("Function already defined");                
            }
            addVariable(function.name.name + "()", function);
            return null;
        }

        public Object visit(NodeStatement.Return stmt) {
            final Object value = interpretExpression(stmt.expr);
            returnFlag = true;
            return value;
        }
        
        public Object visit(NodeStatement.ArrayAssign array) {
            final Object position = interpretExpression(array.position);
            if (!(position instanceof Integer) || (Integer) position < 0) {
                throw new RuntimeException("Array size must be a positive integer: " + position.getClass());
            }
            
            final Object[] objectArray = (Object[]) getVariable(array.qualifier.name + "[]");
            if ((Integer) position >= objectArray.length) {
                throw new RuntimeException("Array index out of bounds: " + position);
            }

            final Object value = interpretExpression(array.expression);
            return objectArray[(Integer) position] = value;
        }

        public Object visit(NodeStatement.Array array) {
            final Object size = interpretExpression(array.length);
            if (!(size instanceof Integer) || (Integer) size < 0) {
                throw new RuntimeException("Array size must be a positive integer: " + size.getClass());
            }

            addVariable(array.qualifier.name + "[]", new Object[(Integer) size]);
            return null;
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
        public Object visit(NodeExpression.Binary node) {

            final Object left = interpretExpression(node.lhs);
            final Object right = interpretExpression(node.rhs);

            switch (node.op) {
                case Exponent:          return Math.pow(evaluate(left), evaluate(right));
                case Multiply:          return evaluate(left) * evaluate(right);
                case Divide:            return evaluate(left) / evaluate(right);
                case Modulo:            return evaluate(left) % evaluate(right);
                case Add:               return evaluate(left) + evaluate(right);
                case Subtract:          return evaluate(left) - evaluate(right);
                case Greater:           return evaluate(left) > evaluate(right);
                case GreaterEqual:      return evaluate(left) >= evaluate(right);
                case Less:              return evaluate(left) < evaluate(right);
                case LessEqual:         return evaluate(left) <= evaluate(right);
                case NotEqual:          return evaluate(left) != evaluate(right);
                case Equal:             return evaluate(left) == evaluate(right);
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
        public Object visit(NodeExpression.Unary node) {
            switch (node.op) {
                case Not:               return ! (Boolean) interpretExpression(node.val);
                case Invert:            return ~ (Integer) interpretExpression(node.val);
                case Negate:            return - evaluate(interpretExpression(node.val));
                case Decrement:
                case Increment:
                default:
                    throw new RuntimeException("Unsupported operation: " + node.op);

            }
        }
        @Override
        public Object visit(NodeExpression.Term node) {
            return interpretTerm(node.val);
        }
    };

    private Object interpretExpression(NodeExpression node) {
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
        public Object visit(NodeTerm.Literal<?> literal) {
            return literal.lit;
        }

        public Object visit(NodeTerm.Variable variable) {
            return getVariable(variable.var.name);
        }

        public Object visit(NodeTerm.Call call) {
            final NodeStatement.Function function = (NodeStatement.Function) getVariable(call.function.name + "()");

            if (call.arguments.size() != function.params.size()) {
                throw new RuntimeException("Function '" + function.name + "()' was called with an incorrect number of arguments");
            }

            enterScope();
            for (int i = 0; i < call.arguments.size(); i += 1) {

                scopes.getLast().put(function.params.get(i).name, interpretExpression(call.arguments.get(i)));
                // addVariable(function.params.get(i).name, interpretExpression(call.arguments.get(i)));
            }

            Object output = null;
            for (NodeStatement statement : function.body.statements) {
                output = interpretStatement(statement);
                
                if (returnFlag) {
                    break;
                }
            }
            exitScope();

            returnFlag = false;
            return output;
        }

        public Object visit(NodeTerm.ArrayAccess array) {
            final Object position = interpretExpression(array.position);
            if (!(position instanceof Integer) || (Integer) position < 0) {
                throw new RuntimeException("Array index must be a positive integer: " + position.getClass());
            }
            
            final Object[] objectArray = (Object[]) getVariable(array.qualifier.name + "[]");
            if ((Integer) position >= objectArray.length) {
                throw new RuntimeException("Array index out of bounds: " + position);
            }

            return objectArray[(Integer) position];
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
    private long evaluate(Object value) {
        if (value == null) return 0;
        else if (value instanceof Date) return ((Date) value).getTime();
        else if (value instanceof Double) return (long) (double) value;
        else if (value instanceof Integer) return (Integer) value;
        else if (value instanceof String) return ((String) value).hashCode();
        else if (value instanceof Long) return (Long) value;
        
        throw new RuntimeException("Atomic expression required to be integer, or integer similar, but is not: " + value);
    }
}
