package com.forenzix.interpreter;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
    private int lineNumber = 0;
    private Object lastResult;
    private MemberAccessor<Object, String, Object> memberAccessCallback;
    private MemberUpdater<Object, String, Object> memberUpdateCallback;

    public Interpreter(String input) {
        this(input, new HashMap<>(), null, null);
    }
    
    public Interpreter(String input, Map<String, Object> variables) {
        this(input, variables, null, null);
    }

    public Interpreter(
        String input, 
        Map<String, Object> variables, 
        MemberAccessor<Object,String,Object> maccess,
        MemberUpdater<Object,String,Object> mupdate
    ) {
        this.parser = new Parser(input);
        this.scopes = new LinkedList<>(List.of(new HashMap<>(variables)));
        this.memberAccessCallback = maccess;
        this.memberUpdateCallback = mupdate;
    }

    /**
     * Variable management.
     * 
     * Crucial for the execution of the interpreter when variables are involved.
     */
    public Interpreter addVariable(String key, Object value) {
        findVariable(key).orElse(scopes.getLast()).put(key, value);
        return this;
    }

    public Map<String, Object> getGlobalScopeVariables() {
        return scopes.getFirst();
    }

    @SuppressWarnings("unchecked")
    public <T> T getVariable(String key) {
        return (T) findVariable(key).orElseThrow(() -> error("Variable " + key + " is undefined")).get(key);
    }

    public boolean defined(String key) {
        return findVariable(key).isPresent();
    }

    public Optional<Map<String, Object>> findVariable(String key) {
        Iterator<Map<String, Object>> itr = scopes.descendingIterator();
        while (itr.hasNext()) {
            final Map<String, Object> scope = itr.next();
            if (scope.containsKey(key)) {
                return Optional.of(scope);
            }
        }

        return Optional.empty();
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

    public void setMemberAccessCallback(MemberAccessor<Object, String, Object> callback) {
        memberAccessCallback = callback;
    }

    public void setMemberUpdateCallback(MemberUpdater<Object, String, Object> callback) {
        memberUpdateCallback = callback;
    }

    private RuntimeException error(String message) {
        return new RuntimeException(message + " (line: " + lineNumber + ")");
    }
    
    private RuntimeException error(Exception exception, String details) {
        final RuntimeException e = new RuntimeException(exception.getMessage() +
            " (line: " + lineNumber + ")" + 
            (details != null ? "\n" + details : "")
            );
        e.addSuppressed(exception);
        return e;
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
        return lastResult = interpretGlobalScope(parser.getRoot());
    }
    
    public Object interpretGlobalScope(NodeScope scope) {
        return interpretScope(scope, true);
    }

    public Object interpretScope(NodeScope scope) {
        return interpretScope(scope, false);
    }

    public Object interpretScope(NodeScope scope, boolean global) {

        if (!global) enterScope();
        Object output = null;
        for (NodeStmt statement : scope.statements) {
            output = interpretStatement(statement);
        }
        if (!global) exitScope();

        return output;
    }

    private final NodeStmt.Visitor statementVisitor = new NodeStmt.Visitor() {
        public Object visit(NodeStmt.Assign assignment) {
            // Uncomment this if you do not want to allow users to use undeclared variables
            // However, it doesn't make much sense to have this on without typing.
            // if (!defined(assignment.qualifier.name)) {
            //     throw error("Assignment to an undefined variable: " + assignment.qualifier.name);
            // }

            final Object value = interpretExpression(assignment.expr);
            addVariable(assignment.var.name, value);
            return value;
        }

        public Object visit(NodeStmt.PropAssign assignment) {
            
            if (memberUpdateCallback == null) {
                throw error("Member updater callback not defined.");
            }

            final Object value = interpretExpression(assignment.expr);

            try {
                memberUpdateCallback.consume((getVariable(assignment.var.name)), assignment.prop.name, value);
            }
            catch (Exception e) {
                throw error(e, 
                    "Additional Details: " + 
                    "Object: " + assignment.var.name + ", " + 
                    "Member: " + assignment.prop.name + ", " + 
                    "Value " + value
                );
            }
            return null;
        }

        public Object visit(NodeStmt.Declare declaration) {

            if (defined(declaration.var.name)) {
                throw error("Redefining an existing variable: " + declaration.var.name);
            }
            
            final Object value = interpretExpression(declaration.expr);
            addVariable(declaration.var.name, value);
            return value;
        }

        public Object visit(NodeStmt.Expr expression) {
            return interpretExpression(expression.expr);
        }

        public Object visit(NodeStmt.If ifStmt) {
            if ((Boolean) interpretExpression(ifStmt.expr)) {
                return interpretScope(ifStmt.succ);
            }
            else if (ifStmt.fail != null) {
                return interpretScope(ifStmt.fail);
            }
            else {
                return null;
            }
        }
        
        public Object visit(NodeStmt.While whileStmt) {
            while ((Boolean) interpretExpression(whileStmt.expr)) {
                interpretScope(whileStmt.scope);
            }
            return null;
        }
        
        public Object visit(NodeStmt.Scope scope) {
            return interpretScope(scope.scope);
        }

        public Object visit(NodeStmt.ForEach loop) {
            final List<?> list = getVariable(loop.list.name);
            enterScope();
            for (Object object : list) {
                addVariable(loop.itr.name, object);
                interpretScope(loop.scope);
            }
            exitScope();
            return null;
        }

        public Object visit(NodeStmt.For loop) {
            enterScope();
            for (interpretStatement(loop.init); (Boolean) interpretExpression(loop.cond); interpretStatement(loop.inc)) {
                interpretScope(loop.scope);
            }
            exitScope();
            return null;
        }
    };

    public Object interpretStatement(NodeStmt statement) {
        return statement == null ? null : statement.host(statementVisitor);
    }

    /**
     * Interpret Boolean Expressrion node.
     * 
     * Since all nodes directly, or indirectly extend BExpr, they get redirected here to their respective interpreters.
     * This interpreter also benefits from Java's inherent evaluation system, where if an expression such as 'true or x'
     * which would normally produce an error if 'x' cannot be evaluated as a boolean would actually evaluate to true.
     * This can be beneficial but potentially hard to debug.
     */

    final NodeExpr.Visitor<Object> nodeExpressionVisitor = new NodeExpr.Visitor<Object>() {
        @Override
        public Object visit(NodeExpr.Binary node) {

            final Object left = interpretExpression(node.lhs);
            // final Object right = interpretExpression(node.rhs);
            final Object right;

            // Special case: String concatenation
            if (left instanceof String && node.op == BinaryOp.Add) {
                return ((String) left).concat(stringValue(right = interpretExpression(node.rhs)));
            }
            
            // Special case: String formatting
            if (left instanceof String && node.op == BinaryOp.Modulo) {
                return String.format((String) left, interpretExpression(node.rhs));
            }

            // Special case: Null check
            if (node.op == BinaryOp.Equal) {
                right = interpretExpression(node.rhs);

                if (left == null || right == null) {
                    return left == right;
                }
            }
            
            else if (node.op == BinaryOp.NotEqual) {
                right = interpretExpression(node.rhs);

                if (left == null || right == null) {
                    return left != right;
                }
            }

            else {
                right = null;
            }

            final Supplier<Object> rGetter = right != null ? () -> right : () -> interpretExpression(node.rhs);

            switch (node.op) {
                case Exponent:          return Math.pow(evaluate(left), evaluate(rGetter.get()));
                case Multiply:          return evaluate(left) * evaluate(rGetter.get());
                case Divide:            return evaluate(left) / evaluate(rGetter.get());
                case Modulo:            return evaluate(left) % evaluate(rGetter.get());
                case Add:               return evaluate(left) + evaluate(rGetter.get());
                case Subtract:          return evaluate(left) - evaluate(rGetter.get());
                case Greater:           return evaluate(left) > evaluate(rGetter.get());
                case GreaterEqual:      return evaluate(left) >= evaluate(rGetter.get());
                case Less:              return evaluate(left) < evaluate(rGetter.get());
                case LessEqual:         return evaluate(left) <= evaluate(rGetter.get());
                case NotEqual:          return evaluate(left) != evaluate(rGetter.get());
                case Equal:             return evaluate(left) == evaluate(rGetter.get());
                case BitAnd:            return (Integer) left & (Integer) rGetter.get();
                case BitOr:             return (Integer) left | (Integer) rGetter.get();
                case BitXor:            return (Integer) left ^ (Integer) rGetter.get();
                case ShiftLeft:         return (Integer) left << (Integer) rGetter.get();
                case ShiftRight:        return (Integer) left >> (Integer) rGetter.get();
                case And:               return (Boolean) left && (Boolean) rGetter.get();
                case Or:                return (Boolean) left || (Boolean) rGetter.get();
                default:
                    throw error("Unsupported operation: " + node.op);
            }
        }

        @Override
        public Object visit(NodeExpr.Unary node) {
            switch (node.op) {
                case Not:               return ! (Boolean) interpretExpression(node.val);
                case Invert:            return ~ (Integer) interpretExpression(node.val);
                case Negate:            return - evaluate(interpretExpression(node.val));
                case Decrement:
                case Increment:
                default:
                    throw error("Unsupported operation: " + node.op);

            }
        }
        @Override
        public Object visit(NodeExpr.Term node) {
            return interpretTerm(node.val);
        }
    };

    private static final SimpleDateFormat format = new SimpleDateFormat("dd/MM/yyyy"); 
    private String stringValue(Object o) {

        if (o instanceof Date) {
            return format.format(o);
        }

        else if (o instanceof Double) {
            return NumberFormat.getInstance().format((Double) o);
        }

        else if (o instanceof BigDecimal) {
            return NumberFormat.getInstance().format(((BigDecimal) o).doubleValue());
        }

        return String.valueOf(o);
    }

    private Object interpretExpression(NodeExpr node) {
        lineNumber = node.lineNumber;
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
        
        public Object visit(NodeTerm.PropAccess maccess) {
            if (memberAccessCallback == null) {
                throw error("Member access callback not defined.");
            }

            try {
                final Object variable = getVariable(maccess.object.name);
                
                // Special Case: List Length
                if (variable instanceof List && Set.of("length", "size").contains(maccess.prop.name.toLowerCase())) {
                    return ((List<?>) variable).size();
                }

                return memberAccessCallback.apply(variable, maccess.prop.name);
            }
            catch (Exception e) {
                throw error(e, 
                    "Additional Details: " + 
                    "Object: " + maccess.object.name + ", " + 
                    "Member: " + maccess.prop.name
                );
            }
        }

        public Object visit(NodeTerm.ArrayLiteral literal) {            
            final List<Object> objects = new LinkedList<>(literal.items.stream()
                .map(expr -> interpretExpression(expr))
                .collect(Collectors.toList()));

            return objects;
        }

        public Object visit(NodeTerm.ArrayAccess access) {
            @SuppressWarnings("unchecked")
            final List<Object> array = (List<Object>) getVariable(access.array.name);
            final Object index = interpretExpression(access.index);

            if (index instanceof Double) {
                return array.get(((Double) index).intValue());
            }
            else {
                return array.get((Integer) index);
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
    private double evaluate(Object value) {
        if (value == null) return 0;
        else if (value instanceof Date) return ((Date) value).getTime();
        else if (value instanceof Double) return (Double) value;
        else if (value instanceof BigDecimal) return ((BigDecimal) value).doubleValue();
        else if (value instanceof Integer) return (Integer) value;
        else if (value instanceof String) return ((String) value).hashCode();
        else if (value instanceof Long) return (Long) value;
        
        throw error("Atomic expression required to be integer, or integer similar, but is not: " + value);
    }

    @FunctionalInterface
    public interface MemberAccessor<S, M, R> {
        public R apply(S source, M member);
    }
    
    @FunctionalInterface
    public interface MemberUpdater<S, M, R> {
        public void consume(S source, M member, R rvalue);
    }
}

