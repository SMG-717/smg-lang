package smg.interpreter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class Interpreter {

    private final LinkedList<Map<String, Object>> scopes;
    private final Parser parser;
    private int line = 0;
    private Object lastResult;
    private MemberAccessor<Object, String, Object> maccess;
    private MemberUpdater<Object, String, Object> mupdate;
    private JumpOperation jump = null;

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
        this.maccess = maccess;
        this.mupdate = mupdate;
    }

    // MARK: Variables and Scopes
    public Map<String, Object> getGlobalScopeVariables() {
        return scopes.getFirst();
    }

    public Interpreter addVar(String key, Object value) {
        return addVar(key, value, false);
    }

    public Interpreter addVar(String key, Object value, boolean shadow) {
        if (shadow) {
            scopes.getLast().put(key, value);
        }
        else {
            findVar(key).orElse(scopes.getLast()).put(key, value);
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T getVar(String key) {
        return (T) findVar(key).orElseThrow(() -> error("Variable " + key + " is undefined")).get(key);
    }

    public boolean defined(String key, boolean allowShadow) {
        if (allowShadow) {
            return scopes.getLast().containsKey(key);
        }
        else {
            return findVar(key).isPresent();
        }
    }

    public boolean defined(String key) {
        return defined(key, false);
    }

    public Optional<Map<String, Object>> findVar(String key) {
        Iterator<Map<String, Object>> itr = scopes.descendingIterator();
        while (itr.hasNext()) {
            final Map<String, Object> scope = itr.next();
            if (scope.containsKey(key)) {
                return Optional.of(scope);
            }
        }

        return Optional.empty();
    }

    private void enterScope() {
        scopes.add(new HashMap<>());
    }

    private void enterScope(Map<String, Object> scope) {
        scopes.add(scope);
    }

    private void exitScope() {
        scopes.removeLast();
    }

    public Interpreter clearVars() {
        scopes.clear();
        return this;
    }

    // MARK: Helpers
    public Object getLastResult() {
        return lastResult;
    }

    public String getTree() {
        return parser.getRoot() != null ? parser.getRoot().toString() : null;
    }

    public void setMaccess(MemberAccessor<Object, String, Object> callback) {
        maccess = callback;
    }

    public void setMupdate(MemberUpdater<Object, String, Object> callback) {
        mupdate = callback;
    }

    private RuntimeException error(String message) {
        return new RuntimeException(message + " (line: " + line + ")");
    }
    
    private RuntimeException error(Exception exception, String details) {
        final RuntimeException e = new RuntimeException(exception.getMessage() +
            " (line: " + line + ")" + 
            (details != null ? "\n" + details : "")
            );
        e.addSuppressed(exception);
        return e;
    }

    private String javaType(Object object) {
        return object == null ? "null" : object.getClass().getSimpleName();
    }
    
    // MARK: Run Scope
    public Object interpret() {
        if (parser.getRoot() == null)
            parser.parse();
        return lastResult = runScope(parser.getRoot(), true);
    }
    
    private Object runScope(NodeScope scope) { return runScope(scope, false); }
    private Object runScope(NodeScope scope, boolean global) {

        if (!global) enterScope();
        Object output = null;
        for (NodeStmt statement : scope.statements) {
            output = runStmt(statement);
            
            if (jump != null) break;
        }
        if (!global) exitScope();

        return output;
    }

    // MARK: Run Statement
    private Object runStmt(NodeStmt s) {return s == null ? null : s.host(stmtVisitor); }
    @SuppressWarnings("unchecked")
    private final NodeStmt.Visitor stmtVisitor = new NodeStmt.Visitor() {
        
        public Object visit(NodeStmt.Scope scope) {
            return runScope(scope.scope);
        }

        public Object visit(NodeStmt.Assign assign) {
            final Object lhs, value;
            if (assign.term instanceof NodeTerm.Variable) {
                lhs = getVar(((NodeTerm.Variable) assign.term).var.name);
                value = calcAssign(assign.op, lhs, runExpr(assign.expr));
                addVar(((NodeTerm.Variable) assign.term).var.name, value);
            }
            else {
                // Find the parent object from the left hand side
                final Object parent, prop, rhs;
                if (assign.term instanceof NodeTerm.ArrayAccess) {
                    final NodeTerm.ArrayAccess t = (NodeTerm.ArrayAccess) assign.term;
                    parent = runTerm(t.array); prop = runExpr(t.index);
                }
                else if (assign.term instanceof NodeTerm.PropAccess) {
                    final NodeTerm.PropAccess t = (NodeTerm.PropAccess) assign.term;
                    parent = runTerm(t.object); prop = t.prop.name;
                }
                else throw error("LHS of an assignment must be an array or property access, or a variable");

                // Evaluate the LHS term using the property accessor
                if (prop instanceof Integer && parent instanceof List) {
                    lhs = ((List<?>) parent).get((Integer) prop);
                }
                else if (prop instanceof String) {
                    lhs = accessProperty(parent, (String) prop);
                }
                else throw error("Invalid property or array accessor: " + prop);

                // Evaluate the RHS and the final value for assignment
                rhs = runExpr(assign.expr);
                value = calcAssign(assign.op, lhs, rhs);
                
                if (prop instanceof Integer && parent instanceof List) {
                   ((List<Object>) parent).set((Integer) prop, value);
                }
                else if (prop instanceof String && parent instanceof Map) {
                    ((Map<String, Object>) parent).put((String) prop, value);
                }
                else if (prop instanceof String && mupdate != null) {
                    mupdate.consume(parent, (String) prop, value);
                }
                else {
                    throw error(String.format("Cannot update property %s of %s (type: %s)", prop, parent, javaType(parent)));
                }
            }
            return value;
        }

        public Object visit(NodeStmt.Expr expression) {
            return runExpr(expression.expr);
        }

        public Object visit(NodeStmt.Declare declaration) {
            if (defined(declaration.var.name, true)) {
                throw error("Redefining an existing variable: " + declaration.var.name);
            }
            
            final Object value = runExpr(declaration.expr);
            addVar(declaration.var.name, value, true);
            return value;
        }

        public Object visit(NodeStmt.If stmt) {
            if ((Boolean) castValue("boolean", runExpr(stmt.expr))) return runScope(stmt.succ);
            else if (stmt.fail != null) return runScope(stmt.fail);
            else return null;
        }

        public Object visit(NodeStmt.TryCatch block) {
            Object value = null;
            try {
                value = runScope(block._try);
            }
            catch (Exception e) {
                if (block._catch == null ) throw e;

                enterScope();
                addVar(block.err.name, e, true);
                value = runScope(block._catch);
                exitScope();
            }
            finally {
                if (block._finally != null ) runScope(block._finally);
            }

            return value;
        }
        
        public Object visit(NodeStmt.While loop) {
            Object value = null;
            while ((Boolean) runExpr(loop.expr)) {
                value = runScope(loop.scope);
                if (jump == JumpOperation.RETURN) break;
                else if (jump == JumpOperation.CONTINUE) { jump = null; continue; }
                else if (jump == JumpOperation.BREAK) { jump = null; break; }
            }
            return value;
        }

        public Object visit(NodeStmt.ForEach loop) {
            final List<?> list = (List<?>) runTerm(loop.list);
            enterScope();
            Object value = null;
            for (Object object : list) {
                addVar(loop.itr.name, object, true);
                value = runScope(loop.scope);
                if (jump == JumpOperation.RETURN) break;
                else if (jump == JumpOperation.CONTINUE) { jump = null; continue; }
                else if (jump == JumpOperation.BREAK) { jump = null; break; }
            }
            exitScope();
            return value;
        }

        public Object visit(NodeStmt.For loop) {
            enterScope();
            Object value = null;
            for (runStmt(loop.init); (Boolean) runExpr(loop.cond); runStmt(loop.inc)) {
                value = runScope(loop.scope);

                if (jump == JumpOperation.RETURN) break;
                else if (jump == JumpOperation.CONTINUE) { jump = null; continue; }
                else if (jump == JumpOperation.BREAK) { jump = null; break; }
            }
            exitScope();
            return value;
        }

        public Object visit(NodeStmt.Function def) {
            final FCapture function = (FCapture) exprVisitor.visit(
                new NodeExpr.Lambda(def.params, def.body)
            );
            addVar(def.var.name, function, true);
            return function;
        }

        public Void visit(NodeStmt.Break statement) {
            jump = JumpOperation.BREAK;
            return null;
        }

        public Void visit(NodeStmt.Continue statement) {
            jump = JumpOperation.CONTINUE;
            return null;
        }

        public Object visit(NodeStmt.Return statement) {
            final Object value = statement.expr != null ? runExpr(statement.expr) : null;
            jump = JumpOperation.RETURN;
            return value;
        }
    };
    
    private Object calcAssign(AssignOp op, Object lhs, Object rhs) {
        switch (op) {
            case AssignEqual: return rhs;
            case AddEqual: return calcBinary(BinaryOp.Add, lhs, rhs);
            case SubEqual: return calcBinary(BinaryOp.Subtract, lhs, rhs);
            case MultiplyEqual: return calcBinary(BinaryOp.Multiply, lhs, rhs);
            case DivideEqual: return calcBinary(BinaryOp.Divide, lhs, rhs);
            case ModEqual: return calcBinary(BinaryOp.Modulo, lhs, rhs);
            case AndEqual: return calcBinary(BinaryOp.And, lhs, rhs);
            case OrEqual: return calcBinary(BinaryOp.Or, lhs, rhs);
        }
        throw error("Unsupported assignment operation: " + op);
    }

    enum JumpOperation {
        RETURN, BREAK, CONTINUE;
    }
    

    // MARK: Run Expression
    private Object runExpr(NodeExpr expr) { line = expr.lineNumber; return expr.host(exprVisitor); }
    @SuppressWarnings("unchecked")
    private final NodeExpr.Visitor exprVisitor = new NodeExpr.Visitor() {
        public Object visit(NodeExpr.Term node) {
            return runTerm(node.val);
        }

        public Object visit(NodeExpr.Binary node) {
            return calcBinary(node.op, runTerm(node.lhs), runTerm(node.rhs));
        }

        public FCapture visit(NodeExpr.Lambda def) {
            final F function = (Object... args) -> {
                enterScope();
                for (int i = 0; i < def.params.size(); i += 1) {
                    addVar(def.params.get(i).param.name, 
                        args[i] != null ? args[i] :
                        runExpr(def.params.get(i)._default),
                        true
                    );
                }

                final Object value = runScope(def.body);
                exitScope();

                jump = null; // Clear jump flag
                return value;
            };

            return new FCapture(scopes, function);
        }
    };

    private Object calcBinaryDouble(BinaryOp op, double lhs, double rhs) {
        switch (op) {            
            case Exponent:          return Math.pow(lhs, rhs);
            case Multiply:          return lhs * rhs;
            case Divide:            return lhs / rhs;
            case Modulo:            return lhs % rhs;
            case Add:               return lhs + rhs;
            case Subtract:          return lhs - rhs;
            case Greater:           return lhs > rhs;
            case GreaterEqual:      return lhs >= rhs;
            case Less:              return lhs < rhs;
            case LessEqual:         return lhs <= rhs;
            case NotEqual:          return lhs != rhs;
            case Equal:             return lhs == rhs;
            default:
        }
        throw error(String.format("Invalid double operation %s", op));
    }

    private Object calcBinaryFloat(BinaryOp op, float lhs, float rhs) {
        switch (op) {
            case Exponent:          return Math.pow(lhs, rhs);
            case Multiply:          return lhs * rhs;
            case Divide:            return lhs / rhs;
            case Modulo:            return lhs % rhs;
            case Add:               return lhs + rhs;
            case Subtract:          return lhs - rhs;
            case Greater:           return lhs > rhs;
            case GreaterEqual:      return lhs >= rhs;
            case Less:              return lhs < rhs;
            case LessEqual:         return lhs <= rhs;
            case NotEqual:          return lhs != rhs;
            case Equal:             return lhs == rhs;
            default:
        }
        throw error(String.format("Invalid float operation %s", op));
    }

    private Object calcBinaryLong(BinaryOp op, long lhs, long rhs) {
        switch (op) {
            case Exponent:          return Math.pow(lhs, rhs);
            case Multiply:          return lhs * rhs;
            case Divide:            return lhs / rhs;
            case Modulo:            return lhs % rhs;
            case Add:               return lhs + rhs;
            case Subtract:          return lhs - rhs;
            case Greater:           return lhs > rhs;
            case GreaterEqual:      return lhs >= rhs;
            case Less:              return lhs < rhs;
            case LessEqual:         return lhs <= rhs;
            case NotEqual:          return lhs != rhs;
            case Equal:             return lhs == rhs;
            case BitAnd:            return lhs & rhs;
            case BitOr:             return lhs | rhs;
            case BitXor:            return lhs ^ rhs;
            case ShiftLeft:         return lhs << rhs;
            case ShiftRight:        return lhs >> rhs;
            default:
        }
        throw error(String.format("Invalid long operation %s", op));
    }

    private Object calcBinaryInteger(BinaryOp op, int lhs, int rhs) {
        switch (op) {
            case Exponent:          return Math.pow(lhs, rhs);
            case Multiply:          return lhs * rhs;
            case Divide:            return lhs / rhs;
            case Modulo:            return lhs % rhs;
            case Add:               return lhs + rhs;
            case Subtract:          return lhs - rhs;
            case Greater:           return lhs > rhs;
            case GreaterEqual:      return lhs >= rhs;
            case Less:              return lhs < rhs;
            case LessEqual:         return lhs <= rhs;
            case NotEqual:          return lhs != rhs;
            case Equal:             return lhs == rhs;
            case BitAnd:            return lhs & rhs;
            case BitOr:             return lhs | rhs;
            case BitXor:            return lhs ^ rhs;
            case ShiftLeft:         return lhs << rhs;
            case ShiftRight:        return lhs >> rhs;
            default:
        }
        throw error(String.format("Invalid integer operation %s", op));
    }

    // Very useful rsource: https://docs.oracle.com/javase/specs/jls/se7/html/jls-5.html
    @SuppressWarnings("unchecked")
    private Object calcBinary(BinaryOp op, Object lhs, Object rhs) {
        if (lhs instanceof String) {
            switch (op) {
                case Add: return ((String) lhs).concat(castValue("string", rhs));
                case Modulo: return ((String) lhs).formatted(rhs);
                case Equal: case NotEqual: break;
                default: throw error("Invalid String binary operation: " + op);
            }
        }
        else if (lhs instanceof List) {
            switch (op) {
                case Add: { 
                    if (rhs instanceof List) ((List<Object>) lhs).addAll((List<Object>) rhs);
                    else ((List<Object>) lhs).add(rhs);

                    return lhs;
                }
                case Equal: case NotEqual: break;
                default: throw error("Invalid List binary operation: " + op);
            }
        }
        else if (lhs instanceof Map) {
            switch (op) {
                case Add: { 
                    if (rhs instanceof Map) {
                        ((Map<Object, Object>) lhs).putAll((Map<Object, Object>) rhs);
                        return lhs;
                    }
                }
                case Equal: case NotEqual: break;
                default: throw error("Invalid Map binary operation: " + op);
            }
        }

        switch (op) {
            case Equal: return lhs.equals(rhs);
            case NotEqual: return !lhs.equals(rhs);
            default:
        }
        
        if (lhs instanceof Double || rhs instanceof Double) {
            return calcBinaryDouble(op, castValue("double", lhs), castValue("double", rhs));
        }
        else if (lhs instanceof Float || rhs instanceof Float) {
            return calcBinaryFloat(op, castValue("float", lhs), castValue("float", rhs));
        }
        else if (lhs instanceof Long || rhs instanceof Long) {
            return calcBinaryLong(op, castValue("long", lhs), castValue("long", rhs));
        }
        else if (lhs instanceof Integer || rhs instanceof Integer) {
            final Object result = calcBinaryInteger(op, castValue("int", lhs), castValue("int", rhs));
            
            if (lhs instanceof Character || rhs instanceof Character) {
                return castValue("char", result);
            }
            else {
                return result;
            }
        }
        else if (lhs instanceof Boolean && rhs instanceof Boolean) {
            switch (op) {
                case And: return (Boolean) lhs && (Boolean) rhs;
                case Or: return (Boolean) lhs || (Boolean) rhs;
            
                default:
                    break;
            }
        }

        throw error(String.format("Invalid operation (%s) %s (%s)", javaType(lhs), op, javaType(rhs)));
    }


    // MARK: Run Term
    private Object runTerm(NodeTerm term) { return term.host(termVisitor); }
    @SuppressWarnings("unchecked")
    final NodeTerm.Visitor termVisitor = new NodeTerm.Visitor() {
        public <T> T visit(NodeTerm.Literal<?> literal) {
            return (T) literal.lit;
        }

        public Object visit(NodeTerm.Variable variable) {
            return getVar(variable.var.name);
        }
        
        public Object visit(NodeTerm.PropAccess paccess) {
            final Object object = runTerm(paccess.object);
            return accessProperty(object, paccess.prop.name);
        }

        public Object visit(NodeTerm.ArrayLiteral arr) {
            return new ArrayList<>(arr.items.stream().map(e -> runExpr(e)).collect(Collectors.toList()));
        }

        public Object visit(NodeTerm.MapLiteral map) {
            return new HashMap<>(map.items.stream().collect(Collectors.toMap(e -> e.key.name, e -> runExpr(e.value))));
        }

        public Object visit(NodeTerm.ArrayAccess access) {
            final Object object = runTerm(access.array);
            final Object index = runExpr(access.index);
            if (index instanceof String) {
                return accessProperty(object, (String) index);
            }
            else if (index instanceof Integer && object instanceof List) {
                return ((List<?>) object).get((Integer) index);
            }
            
            throw error("Invalid array access: " + access);
        }

        public Object visit(NodeTerm.Expr expr) {
            return runExpr(expr.expr);
        }

        public Object visit(NodeTerm.UnaryExpr expr) {
            return calcUnary(expr.op, runTerm(expr.val));
        }

        public Object visit(NodeTerm.Call call) {
            
            final Object f = runTerm(call.f);
            final Object[] argExprs = call.args.stream().map(a -> runExpr(a)).toArray();
            if (f instanceof FCapture) {
                enterScope(((FCapture) f).variables);
                final Object value = ((FCapture) f).invoke(argExprs);
                exitScope();
                return value;
            }
            else if (f instanceof F) {
                final Object value = ((F) f).apply(argExprs);
                return value;
            }
            else if (f instanceof F0) {
                ((F0) f).apply(argExprs);
                return null;
            }
            
            throw error("Unsupported function type: " + javaType(f));
        }

        public Object visit(NodeTerm.Cast cast) {
            final Object value = runTerm(cast.object);
            return castValue(cast.type.type, value);
        }
    };

    private Object accessProperty(Object object, String prop) {
        if (object instanceof Map) {
            return ((Map<?, ?>) object).get(prop);
        }
        else if (object instanceof String) {
            switch (prop.toLowerCase()) {
                case "size":
                case "length": return ((String) object).length();
                case "split": return ((F) (args) -> { return ((String) args[0]).split((String) args[1]); });

                // Add more string properties here!
            }

            throw error("Invalid String property: " + prop);
        }
        else if (object instanceof List) {
            switch (prop.toLowerCase()) {
                case "size":
                case "length": return ((List<?>) object).size();
                // Add more list properties here!                
            }

            throw error("Invalid List property: " + prop);
        }

        if (maccess == null) throw error("Member access not defined.");
        try {
            return maccess.apply(object, prop);
        }
        catch (Exception e) {
            throw error(e, String.format("Additional Details - Operation: %s", object, prop));
        }
    }

    private Object calcUnary(UnaryOp op, Object value) {
        switch (op) {
            case Not: return !(Boolean) castValue("boolean", value);
            case Negate: {
                if (value instanceof Double) return - (Double) value;
                else if (value instanceof Integer) return - (Integer) value;
                else if (value instanceof Float) return - (Float) value;
                else if (value instanceof Long) return - (Long) value;
                break;
            }
            default:
        }
        throw error(String.format("Invalid unary operation '%s' on value of type %s", op, javaType(value)));
    }

    @SuppressWarnings("unchecked")
    private <R> R castValue(String type, Object value) {
        switch (type) {
            case "string": return (R) String.valueOf(value);
            case "int": {
                if (value == null) return (R) (Integer) 0;
                else if (value instanceof Integer) return (R) value;
                else if (value instanceof Boolean) return (R) (Integer) (((Boolean) value) ? 1 : 0);
                else if (value instanceof Character) return (R) (Integer) (int) ((Character) value).charValue();
                else if (value instanceof Double) return (R) (Integer) ((Double) value).intValue();
                else if (value instanceof Float) return (R) (Integer) ((Float) value).intValue();
                else if (value instanceof Long) return (R) (Integer) ((Long) value).intValue();
                else if (value instanceof String) return (R) Integer.valueOf((String) value);
                break;
            }

            case "long": {
                if (value == null) return (R) (Long) 0L;
                else if (value instanceof Long) return (R) value;
                else if (value instanceof Boolean) return (R) (Long) (((Boolean) value) ? 1L : 0L);
                else if (value instanceof Integer) return (R) (Long) ((Integer) value).longValue();
                else if (value instanceof Double) return (R) (Long) ((Double) value).longValue();
                else if (value instanceof Float) return (R) (Long) ((Float) value).longValue();
                else if (value instanceof String) return (R) Integer.valueOf((String) value);
                break;
            }
            
            case "char": {
                if (value == null) return (R) (Character) '\0';
                else if (value instanceof Character) return (R) value;
                else if (value instanceof Boolean) return (R) (Character) (((Boolean) value) ? '1' : '0');
                else if (value instanceof Integer) return (R) (Character) (char) (int) value;
                else if (value instanceof Double) return (R) (Character) (char) ((Double) value).intValue();
                else if (value instanceof Float) return (R) (Character) (char) ((Float) value).intValue();
                else if (value instanceof String) {
                    final String s = (String) value;
                    if (s.length() == 0) return (R) (Character) '\0';
                    else if (s.length() == 1) return (R) (Character)  s.charAt(0);
                    throw error("Cannot convert string of length 2 or more into char");
                }
                break;
            }

            case "double": {
                if (value == null) return (R) (Double) 0.0D;
                else if (value instanceof Double) return (R) value;
                else if (value instanceof Boolean) return (R) (Double) (((Boolean) value) ? 1.0D : 0.0D);
                else if (value instanceof Integer) return (R) (Double) ((Integer) value).doubleValue();
                else if (value instanceof Long) return (R) (Double) ((Long) value).doubleValue();
                else if (value instanceof Float) return (R) (Double) ((Float) value).doubleValue();
                else if (value instanceof String) return (R) Double.valueOf((String) value);
                break;
            }

            case "float": {
                if (value == null) return (R) (Float) 0.0F;
                else if (value instanceof Float) return (R) value;
                else if (value instanceof Boolean) return (R) (Float) (((Boolean) value) ? 1.0F : 0.0F);
                else if (value instanceof Integer) return (R) (Float) ((Integer) value).floatValue();
                else if (value instanceof Long) return (R) (Float) ((Long) value).floatValue();
                else if (value instanceof Double) return (R) (Float) ((Double) value).floatValue();
                else if (value instanceof String) return (R) Float.valueOf((String) value);
                break;
            }

            case "boolean": {
                if (value == null) return (R) (Boolean) false;
                else if (value instanceof Boolean) return (R) (Boolean) value;
                else if (value instanceof Integer) return (R) (Boolean) (((Integer) value) != 0);
                else if (value instanceof Double) return (R) (Boolean) (((Double) value) != 0.0D);
                else if (value instanceof Float) return (R) (Boolean) (((Float) value) != 0.0F);
                else if (value instanceof Long) return (R) (Boolean) (((Long) value) != 0L);
                else if (value instanceof String) return (R) (Boolean) !((String) value).isEmpty();
                break;
            }
        }

        throw error(String.format("Casting from %s to %s is not allowed.", javaType(value), type));
    }


    @FunctionalInterface
    public static interface MemberAccessor<S, M, R> {
        public R apply(S source, M member);
    }
    
    @FunctionalInterface
    public static interface MemberUpdater<S, M, R> {
        public void consume(S source, M member, R rvalue);
    }
    
    @FunctionalInterface
    public static interface F { public Object apply(Object... args); }
    
    @FunctionalInterface
    public static interface F0 { public void apply(Object... args); }
    
    private static class FCapture {
        public final Map<String, Object> variables;
        private final Object function;

        public FCapture(List<Map<String, Object>> stack, Object f) {
            variables = new HashMap<>();
            for (Map<String, Object> map : stack) {
                variables.putAll(map);
            }

            if ((function = f) == null) {
                throw new IllegalArgumentException("Supplying null for function is not allowed");
            }
        }

        public Object invoke(Object... args) {
            if (function instanceof F) {
                return ((F) function).apply(args);
            }
            else if (function instanceof F0) {
                ((F0) function).apply(args);
                return null;
            }
            else {
                throw new RuntimeException("Unsupported function type: " + function.getClass().getSimpleName());
            }
        }
    }
}

